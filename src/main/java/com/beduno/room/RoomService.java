package com.beduno.room;

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
            "name", "name",
            "floor", "floor",
            "capacity", "capacity",
            "genderRule", "genderRule",
            "status", "status",
            "createdAt", "createdAt",
            "updatedAt", "updatedAt");

    private final RoomRepository roomRepository;
    private final RoomMapper roomMapper;
    private final PropertyService propertyService;
    private final AuditService auditService;
    private final StayRepository stayRepository;

    @Transactional(readOnly = true)
    public PageResponse<RoomResponse> findAllByPropertyId(UUID propertyId, Pageable pageable) {
        var agencyId = TenantContext.requireAgencyId();
        propertyService.getPropertyOrThrow(propertyId);
        var page = roomRepository.findAllByAgencyIdAndPropertyId(
                agencyId, propertyId, SortFields.translate(pageable, SORTABLE));
        return PageResponse.of(page.map(roomMapper::toResponse));
    }

    @Transactional(readOnly = true)
    public RoomResponse findById(UUID propertyId, UUID roomId) {
        var room = getRoomOrThrow(propertyId, roomId);
        return roomMapper.toResponse(room);
    }

    @Transactional
    public RoomResponse create(UUID propertyId, CreateRoomRequest request) {
        var agencyId = TenantContext.requireAgencyId();
        propertyService.getPropertyOrThrow(propertyId);

        if (roomRepository.existsByPropertyIdAndName(propertyId, request.name())) {
            throw new ConflictException("error.room.name_exists");
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

        if (!room.getName().equals(request.name()) && roomRepository.existsByPropertyIdAndName(propertyId, request.name())) {
            throw new ConflictException("error.room.name_exists");
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

    private Map<String, Object> snapshot(Room room) {
        var map = new LinkedHashMap<String, Object>();
        map.put("name", room.getName());
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
