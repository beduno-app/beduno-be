package com.beduno.room;

import java.util.stream.Collectors;
import java.util.List;
import java.time.Clock;
import java.time.LocalDate;
import com.beduno.bed.Bed;
import com.beduno.bed.BedRepository;
import com.beduno.bed.BedStatus;
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
import java.util.Set;
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
    private final BedRepository bedRepository;
    private final Clock clock;

    @Transactional(readOnly = true)
    public PageResponse<RoomResponse> findAllByPropertyId(UUID propertyId, Pageable pageable) {
        var agencyId = TenantContext.requireAgencyId();
        propertyService.getPropertyOrThrow(propertyId);
        var page = roomRepository.findAllByAgencyIdAndPropertyId(
                agencyId, propertyId, SortFields.translate(pageable, SORTABLE));
        var occupants = occupantsByRoom(agencyId, propertyId);
        var roomIds = page.getContent().stream().map(Room::getId).collect(Collectors.toSet());
        var beds = bedsByRoom(agencyId, roomIds);
        return PageResponse.of(page.map(room -> withOccupants(room, beds, occupants)));
    }

    @Transactional(readOnly = true)
    public RoomResponse findById(UUID propertyId, UUID roomId) {
        var room = getRoomOrThrow(propertyId, roomId);
        var agencyId = TenantContext.requireAgencyId();
        var beds = bedsByRoom(agencyId, Set.of(roomId));
        return withOccupants(room, beds, occupantsByRoom(agencyId, propertyId));
    }

    @Transactional
    public RoomResponse create(UUID propertyId, CreateRoomRequest request) {
        var agencyId = TenantContext.requireAgencyId();
        propertyService.getPropertyOrThrow(propertyId);

        if (roomRepository.existsByPropertyIdAndRoomNumber(propertyId, request.roomNumber())) {
            throw new ConflictException("error.room.number_exists");
        }

        var room = roomMapper.toEntity(request);
        room.setAgencyId(agencyId);
        room.setPropertyId(propertyId);
        room = roomRepository.save(room);
        auditService.log(agencyId, currentUserId(), AuditEntityType.ROOM, room.getId(),
                AuditAction.CREATED, null, snapshot(room), null);
        // A room that was just created has no beds and no stays, but occupants must still be an
        // empty list: the client types it as an array and a null is not one.
        return roomMapper.toResponse(room).withOccupancy(0, 0, 0, List.of());
    }

    @Transactional
    public RoomResponse update(UUID propertyId, UUID roomId, UpdateRoomRequest request) {
        var room = getRoomOrThrow(propertyId, roomId);

        if (!room.getRoomNumber().equals(request.roomNumber()) && roomRepository.existsByPropertyIdAndRoomNumber(propertyId, request.roomNumber())) {
            throw new ConflictException("error.room.number_exists");
        }

        var previous = snapshot(room);
        roomMapper.updateEntity(request, room);
        room = roomRepository.save(room);
        auditService.log(room.getAgencyId(), currentUserId(), AuditEntityType.ROOM, room.getId(),
                AuditAction.UPDATED, previous, snapshot(room), null);
        // Unlike create, an existing room can already have beds and occupants, so this reads the
        // real counts rather than assuming zero -- editing a room must not blank either one out.
        var agencyId = room.getAgencyId();
        var beds = bedsByRoom(agencyId, Set.of(roomId));
        return withOccupants(room, beds, occupantsByRoom(agencyId, propertyId));
    }

    @Transactional
    public void delete(UUID propertyId, UUID roomId) {
        var room = getRoomOrThrow(propertyId, roomId);

        // stays.room_id and beds.room_id are both RESTRICT foreign keys — see PropertyService.delete.
        if (stayRepository.countByAgencyIdAndRoomId(room.getAgencyId(), roomId) > 0) {
            throw new ConflictException("error.room.has_stays");
        }
        if (bedRepository.countByAgencyIdAndRoomId(room.getAgencyId(), roomId) > 0) {
            throw new ConflictException("error.room.has_beds");
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
                agencyId, propertyId, LocalDate.now(clock), OCCUPYING_STATUSES);
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

    /**
     * Loaded once per request for the room(s) at hand, mirroring occupantsByRoom's batching: a
     * page of rooms issues one beds query rather than one per room.
     */
    private Map<UUID, List<Bed>> bedsByRoom(UUID agencyId, Set<UUID> roomIds) {
        if (roomIds.isEmpty()) {
            return Map.of();
        }
        return bedRepository.findAllByAgencyIdAndRoomIdIn(agencyId, roomIds).stream()
                .collect(Collectors.groupingBy(Bed::getRoomId));
    }

    private RoomResponse withOccupants(Room room, Map<UUID, List<Bed>> bedsByRoom,
                                        Map<UUID, List<RoomOccupant>> occupantsByRoom) {
        var beds = bedsByRoom.getOrDefault(room.getId(), List.of());
        var activeBedCount = (int) beds.stream().filter(b -> b.getStatus() == BedStatus.ACTIVE).count();
        var occupants = occupantsByRoom.getOrDefault(room.getId(), List.of());
        return roomMapper.toResponse(room)
                .withOccupancy(beds.size(), activeBedCount, occupants.size(), occupants);
    }

    private Map<String, Object> snapshot(Room room) {
        var map = new LinkedHashMap<String, Object>();
        map.put("roomNumber", room.getRoomNumber());
        map.put("status", room.getStatus().name());
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
