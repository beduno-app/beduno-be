package com.bedok.room;

import com.bedok.common.exception.ConflictException;
import com.bedok.common.exception.NotFoundException;
import com.bedok.common.exception.ValidationException;
import com.bedok.common.model.PageResponse;
import com.bedok.common.security.TenantContext;
import com.bedok.property.PropertyService;
import com.bedok.room.dto.CreateRoomRequest;
import com.bedok.room.dto.RoomResponse;
import com.bedok.room.dto.UpdateRoomRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class RoomService {

    private final RoomRepository roomRepository;
    private final RoomMapper roomMapper;
    private final PropertyService propertyService;

    @Transactional(readOnly = true)
    public PageResponse<RoomResponse> findAllByPropertyId(UUID propertyId, Pageable pageable) {
        var agencyId = TenantContext.requireAgencyId();
        propertyService.getPropertyOrThrow(propertyId);
        var page = roomRepository.findAllByAgencyIdAndPropertyId(agencyId, propertyId, pageable);
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

        roomMapper.updateEntity(request, room);
        room = roomRepository.save(room);
        return roomMapper.toResponse(room);
    }

    @Transactional
    public void delete(UUID propertyId, UUID roomId) {
        var room = getRoomOrThrow(propertyId, roomId);
        roomRepository.delete(room);
    }

    private Room getRoomOrThrow(UUID propertyId, UUID roomId) {
        var agencyId = TenantContext.requireAgencyId();
        propertyService.getPropertyOrThrow(propertyId);
        return roomRepository.findByIdAndAgencyIdAndPropertyId(roomId, agencyId, propertyId)
                .orElseThrow(() -> new NotFoundException("error.room.not_found"));
    }
}
