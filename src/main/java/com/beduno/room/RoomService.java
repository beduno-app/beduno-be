package com.beduno.room;

import java.util.stream.Collectors;
import java.util.List;
import java.time.LocalDate;
import com.beduno.worker.WorkerRepository;
import com.beduno.worker.Worker;
import com.beduno.stay.StayStatus;
import com.beduno.stay.Stay;
import com.beduno.room.dto.RoomOccupant;
import com.beduno.common.model.SortFields;
import com.beduno.audit.AuditAction;
import com.beduno.audit.AuditEntityType;
import com.beduno.audit.AuditService;
import com.beduno.common.exception.ConflictException;
import com.beduno.common.exception.NotFoundException;
import com.beduno.common.exception.ValidationException;
import com.beduno.common.model.PageResponse;
import com.beduno.common.security.CurrentUser;
import com.beduno.common.security.TenantContext;
import com.beduno.property.PropertyService;
import com.beduno.room.dto.CreateRoomRequest;
import com.beduno.room.dto.RoomResponse;
import com.beduno.room.dto.UpdateRoomRequest;
import com.beduno.stay.StayRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class RoomService {

    /**
     * Rooms come from a derived query, so these map to entity properties rather than columns.
     * There is no roomNumber: the field a client sees in the response is name.
     */
    private static final Map<String, String> SORTABLE = Map.of(
            "roomNumber", "roomNumber",
            "floor", "floor",
            "capacity", "capacity",
            "blockedSpots", "blockedSpots",
            "genderRule", "genderRule",
            "status", "status",
            "createdAt", "createdAt",
            "updatedAt", "updatedAt");

    private final RoomRepository roomRepository;
    private final RoomMapper roomMapper;
    private final PropertyService propertyService;
    private final AuditService auditService;
    private final StayRepository stayRepository;
    private final WorkerRepository workerRepository;

    @Transactional(readOnly = true)
    public PageResponse<RoomResponse> findAllByPropertyId(UUID propertyId, Pageable pageable) {
        var agencyId = TenantContext.requireAgencyId();
        propertyService.getPropertyOrThrow(propertyId);
        var page = roomRepository.findAllByAgencyIdAndPropertyId(
                agencyId, propertyId, SortFields.translate(pageable, SORTABLE));
        var occupants = occupantsByRoom(agencyId, propertyId);
        return PageResponse.of(page.map(room -> withOccupants(room, occupants)));
    }

    @Transactional(readOnly = true)
    public RoomResponse findById(UUID propertyId, UUID roomId) {
        var room = getRoomOrThrow(propertyId, roomId);
        return withOccupants(room, occupantsByRoom(TenantContext.requireAgencyId(), propertyId));
    }

    @Transactional
    public RoomResponse create(UUID propertyId, CreateRoomRequest request) {
        var agencyId = TenantContext.requireAgencyId();
        propertyService.getPropertyOrThrow(propertyId);

        if (roomRepository.existsByPropertyIdAndRoomNumber(propertyId, request.roomNumber())) {
            throw new ConflictException("error.room.number_exists");
        }

        if (request.blockedSpots() > request.capacity()) {
            throw new ValidationException("error.room.blocked_spots_exceed_capacity");
        }

        var room = roomMapper.toEntity(request);
        room.setAgencyId(agencyId);
        room.setPropertyId(propertyId);
        room = roomRepository.save(room);
        auditService.log(agencyId, currentUserId(), AuditEntityType.ROOM, room.getId(),
                AuditAction.CREATED, null, snapshot(room), null);
        return roomMapper.toResponse(room);
    }

    @Transactional
    public RoomResponse update(UUID propertyId, UUID roomId, UpdateRoomRequest request) {
        var room = getRoomOrThrow(propertyId, roomId);

        if (request.blockedSpots() > request.capacity()) {
            throw new ValidationException("error.room.blocked_spots_exceed_capacity");
        }

        if (!room.getRoomNumber().equals(request.roomNumber()) && roomRepository.existsByPropertyIdAndRoomNumber(propertyId, request.roomNumber())) {
            throw new ConflictException("error.room.number_exists");
        }

        var previous = snapshot(room);
        roomMapper.updateEntity(request, room);
        room = roomRepository.save(room);
        auditService.log(room.getAgencyId(), currentUserId(), AuditEntityType.ROOM, room.getId(),
                AuditAction.UPDATED, previous, snapshot(room), null);
        return roomMapper.toResponse(room);
    }

    @Transactional
    public void delete(UUID propertyId, UUID roomId) {
        var room = getRoomOrThrow(propertyId, roomId);

        // stays.room_id is a RESTRICT foreign key — see PropertyService.delete.
        if (stayRepository.countByAgencyIdAndRoomId(room.getAgencyId(), roomId) > 0) {
            throw new ConflictException("error.room.has_stays");
        }

        var previous = snapshot(room);
        roomRepository.delete(room);
        auditService.log(room.getAgencyId(), currentUserId(), AuditEntityType.ROOM, room.getId(),
                AuditAction.DELETED, previous, null, null);
    }

    private UUID currentUserId() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof CurrentUser currentUser) {
            return currentUser.userId();
        }
        return null;
    }

    /**
     * Occupancy means CHECKED_IN today -- who is actually in the room -- matching what the
     * occupancy board counts. A stay that is merely expected has not taken the bed yet, and
     * showing it as occupancy would misreport the one number the room card exists to convey.
     */
    private static final List<StayStatus> OCCUPYING_STATUSES = List.of(StayStatus.CHECKED_IN);

    /**
     * Loaded once per request for the whole property rather than per room: a page of rooms would
     * otherwise issue a stay query and a worker query each.
     */
    private Map<UUID, List<RoomOccupant>> occupantsByRoom(UUID agencyId, UUID propertyId) {
        var stays = stayRepository.findActiveStaysForPropertyOnDate(
                agencyId, propertyId, LocalDate.now(), OCCUPYING_STATUSES);
        if (stays.isEmpty()) {
            return Map.of();
        }
        var workerIds = stays.stream().map(Stay::getWorkerId).collect(Collectors.toSet());
        var workersById = workerRepository.findAllByAgencyIdAndIdIn(agencyId, workerIds).stream()
                .collect(Collectors.toMap(Worker::getId, w -> w));
        return stays.stream()
                .filter(stay -> workersById.containsKey(stay.getWorkerId()))
                .map(stay -> {
                    var w = workersById.get(stay.getWorkerId());
                    return Map.entry(stay.getRoomId(), new RoomOccupant(
                            stay.getId(),
                            new RoomOccupant.OccupantWorker(
                                    w.getId(), w.getInternalId(), w.getFirstName(),
                                    w.getLastName(), w.getGender()),
                            stay.getDateFrom(), stay.getDateTo(), stay.getStatus()));
                })
                .collect(Collectors.groupingBy(Map.Entry::getKey,
                        Collectors.mapping(Map.Entry::getValue, Collectors.toList())));
    }

    private RoomResponse withOccupants(Room room, Map<UUID, List<RoomOccupant>> occupantsByRoom) {
        var occupants = occupantsByRoom.getOrDefault(room.getId(), List.of());
        return roomMapper.toResponse(room).withOccupancy(occupants.size(), occupants);
    }

    private Map<String, Object> snapshot(Room room) {
        var map = new LinkedHashMap<String, Object>();
        map.put("roomNumber", room.getRoomNumber());
        map.put("status", room.getStatus().name());
        map.put("capacity", room.getCapacity());
        map.put("blockedSpots", room.getBlockedSpots());
        map.put("genderRule", room.getGenderRule().name());
        return map;
    }

    private Room getRoomOrThrow(UUID propertyId, UUID roomId) {
        var agencyId = TenantContext.requireAgencyId();
        propertyService.getPropertyOrThrow(propertyId);
        return roomRepository.findByIdAndAgencyIdAndPropertyId(roomId, agencyId, propertyId)
                .orElseThrow(() -> new NotFoundException("error.room.not_found"));
    }
}
