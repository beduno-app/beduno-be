package com.beduno.bed;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.beduno.audit.AuditAction;
import com.beduno.audit.AuditEntityType;
import com.beduno.audit.AuditService;
import com.beduno.bed.dto.BedResponse;
import com.beduno.bed.dto.CreateBedRequest;
import com.beduno.bed.dto.UpdateBedRequest;
import com.beduno.common.exception.ConflictException;
import com.beduno.common.exception.NotFoundException;
import com.beduno.common.security.CurrentUser;
import com.beduno.common.security.TenantContext;
import com.beduno.property.PropertyService;
import com.beduno.room.Room;
import com.beduno.room.RoomRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class BedService {

    private final BedRepository bedRepository;
    private final BedMapper bedMapper;
    private final RoomRepository roomRepository;
    private final PropertyService propertyService;
    private final AuditService auditService;

    @Transactional(readOnly = true)
    public List<BedResponse> findAllByRoomId(UUID propertyId, UUID roomId) {
        var agencyId = TenantContext.requireAgencyId();
        getRoomOrThrow(propertyId, roomId, agencyId);
        return bedRepository.findAllByAgencyIdAndRoomId(agencyId, roomId).stream()
                .map(bedMapper::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public BedResponse findById(UUID propertyId, UUID roomId, UUID bedId) {
        var agencyId = TenantContext.requireAgencyId();
        getRoomOrThrow(propertyId, roomId, agencyId);
        return bedMapper.toResponse(getBedOrThrow(roomId, bedId, agencyId));
    }

    @Transactional
    public BedResponse create(UUID propertyId, UUID roomId, CreateBedRequest request) {
        var agencyId = TenantContext.requireAgencyId();
        getRoomOrThrow(propertyId, roomId, agencyId);

        if (bedRepository.existsByRoomIdAndLabel(roomId, request.label())) {
            throw new ConflictException("error.bed.label_exists");
        }

        var bed = new Bed();
        bed.setAgencyId(agencyId);
        bed.setRoomId(roomId);
        bed.setLabel(request.label());
        bed = bedRepository.save(bed);
        auditService.log(agencyId, currentUserId(), AuditEntityType.BED, bed.getId(),
                AuditAction.CREATED, null, snapshot(bed), null);
        return bedMapper.toResponse(bed);
    }

    /**
     * Labels the next {@code count} beds as sequential integers after the room's current highest
     * numeric label, starting at 1 if the room has no beds yet or none of its labels are purely
     * numeric -- a renamed bed like "top bunk" must not block numbering the rest.
     */
    @Transactional
    public List<BedResponse> bulkGenerate(UUID propertyId, UUID roomId, int count) {
        var agencyId = TenantContext.requireAgencyId();
        getRoomOrThrow(propertyId, roomId, agencyId);

        var existing = bedRepository.findAllByAgencyIdAndRoomId(agencyId, roomId);
        var nextLabel = highestNumericLabel(existing) + 1;

        var created = new ArrayList<Bed>(count);
        for (var i = 0; i < count; i++) {
            var bed = new Bed();
            bed.setAgencyId(agencyId);
            bed.setRoomId(roomId);
            bed.setLabel(String.valueOf(nextLabel + i));
            created.add(bedRepository.save(bed));
        }
        created.forEach(bed -> auditService.log(agencyId, currentUserId(), AuditEntityType.BED,
                bed.getId(), AuditAction.CREATED, null, snapshot(bed), null));
        return created.stream().map(bedMapper::toResponse).toList();
    }

    private long highestNumericLabel(List<Bed> beds) {
        return beds.stream()
                .map(Bed::getLabel)
                .filter(label -> label.matches("\\d+"))
                .mapToLong(Long::parseLong)
                .max()
                .orElse(0L);
    }

    @Transactional
    public BedResponse update(UUID propertyId, UUID roomId, UUID bedId, UpdateBedRequest request) {
        var agencyId = TenantContext.requireAgencyId();
        getRoomOrThrow(propertyId, roomId, agencyId);
        var bed = getBedOrThrow(roomId, bedId, agencyId);

        if (!bed.getLabel().equals(request.label())
                && bedRepository.existsByRoomIdAndLabel(roomId, request.label())) {
            throw new ConflictException("error.bed.label_exists");
        }

        var previous = snapshot(bed);
        bedMapper.updateEntity(request, bed);
        bed = bedRepository.save(bed);
        auditService.log(agencyId, currentUserId(), AuditEntityType.BED, bed.getId(),
                AuditAction.UPDATED, previous, snapshot(bed), null);
        return bedMapper.toResponse(bed);
    }

    /**
     * No stay-reference guard yet: stays.bed_id doesn't exist until phase 2, so nothing can
     * possibly reference a bed at this point in the migration sequence. The real guard (mirroring
     * RoomService.delete) lands once that column and its RESTRICT foreign key exist.
     */
    @Transactional
    public void delete(UUID propertyId, UUID roomId, UUID bedId) {
        var agencyId = TenantContext.requireAgencyId();
        getRoomOrThrow(propertyId, roomId, agencyId);
        var bed = getBedOrThrow(roomId, bedId, agencyId);

        var previous = snapshot(bed);
        bedRepository.delete(bed);
        auditService.log(agencyId, currentUserId(), AuditEntityType.BED, bed.getId(),
                AuditAction.DELETED, previous, null, null);
    }

    private Room getRoomOrThrow(UUID propertyId, UUID roomId, UUID agencyId) {
        propertyService.getPropertyOrThrow(propertyId);
        return roomRepository.findByIdAndAgencyIdAndPropertyId(roomId, agencyId, propertyId)
                .orElseThrow(() -> new NotFoundException("error.room.not_found"));
    }

    private Bed getBedOrThrow(UUID roomId, UUID bedId, UUID agencyId) {
        return bedRepository.findByIdAndAgencyIdAndRoomId(bedId, agencyId, roomId)
                .orElseThrow(() -> new NotFoundException("error.bed.not_found"));
    }

    private UUID currentUserId() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof CurrentUser currentUser) {
            return currentUser.userId();
        }
        return null;
    }

    private Map<String, Object> snapshot(Bed bed) {
        var map = new LinkedHashMap<String, Object>();
        map.put("roomId", bed.getRoomId());
        map.put("label", bed.getLabel());
        map.put("status", bed.getStatus().name());
        return map;
    }
}
