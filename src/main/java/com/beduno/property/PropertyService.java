package com.beduno.property;

import com.beduno.common.model.SortFields;
import com.beduno.audit.AuditAction;
import com.beduno.audit.AuditEntityType;
import com.beduno.audit.AuditService;
import com.beduno.common.exception.ConflictException;
import com.beduno.common.exception.NotFoundException;
import com.beduno.common.model.PageResponse;
import com.beduno.common.security.SecurityUtils;
import com.beduno.common.security.TenantContext;
import com.beduno.property.dto.CreatePropertyRequest;
import com.beduno.property.dto.PropertyResponse;
import com.beduno.property.dto.UpdatePropertyRequest;
import com.beduno.room.RoomRepository;
import com.beduno.stay.StayRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class PropertyService {

    /**
     * The fields a client may sort properties by, mapped to the columns the native query orders by.
     */
    private static final Map<String, String> SORTABLE = Map.of(
            "name", "name",
            "address", "address",
            "city", "city",
            "status", "status",
            "createdAt", "created_at",
            "updatedAt", "updated_at");

    private final PropertyRepository propertyRepository;
    private final PropertyMapper propertyMapper;
    private final AuditService auditService;
    private final RoomRepository roomRepository;
    private final StayRepository stayRepository;

    @Transactional(readOnly = true)
    public PageResponse<PropertyResponse> findAll(PropertyStatus status, String search, Pageable pageable) {
        var agencyId = TenantContext.requireAgencyId();
        var page = propertyRepository.findAllByAgencyIdWithFilters(
                agencyId,
                status != null ? status.name() : null,
                search, SortFields.translate(pageable, SORTABLE));
        return PageResponse.of(page.map(propertyMapper::toResponse));
    }

    @Transactional(readOnly = true)
    public PropertyResponse findById(UUID id) {
        var property = getPropertyOrThrow(id);
        return propertyMapper.toResponse(property);
    }

    @Transactional
    public PropertyResponse create(CreatePropertyRequest request) {
        var agencyId = TenantContext.requireAgencyId();
        var property = propertyMapper.toEntity(request);
        property.setAgencyId(agencyId);
        property = propertyRepository.save(property);
        auditService.log(agencyId, SecurityUtils.currentUserId(), AuditEntityType.PROPERTY, property.getId(),
                AuditAction.CREATED, null, snapshot(property), null);
        return propertyMapper.toResponse(property);
    }

    @Transactional
    public PropertyResponse update(UUID id, UpdatePropertyRequest request) {
        var property = getPropertyOrThrow(id);
        var previous = snapshot(property);
        propertyMapper.updateEntity(request, property);
        property = propertyRepository.save(property);
        auditService.log(property.getAgencyId(), SecurityUtils.currentUserId(), AuditEntityType.PROPERTY, property.getId(),
                AuditAction.UPDATED, previous, snapshot(property), null);
        return propertyMapper.toResponse(property);
    }

    @Transactional
    public void delete(UUID id) {
        var property = getPropertyOrThrow(id);
        var agencyId = property.getAgencyId();

        // rooms.property_id and stays.property_id are RESTRICT foreign keys, so a
        // referenced property cannot be removed. Refuse with 409 rather than letting
        // the delete fail at the database as an opaque 500.
        if (roomRepository.countByAgencyIdAndPropertyId(agencyId, id) > 0) {
            throw new ConflictException("error.property.has_rooms");
        }
        if (stayRepository.countByAgencyIdAndPropertyId(agencyId, id) > 0) {
            throw new ConflictException("error.property.has_stays");
        }

        var previous = snapshot(property);
        propertyRepository.delete(property);
        auditService.log(property.getAgencyId(), SecurityUtils.currentUserId(), AuditEntityType.PROPERTY, property.getId(),
                AuditAction.DELETED, previous, null, null);
    }


    private Map<String, Object> snapshot(Property property) {
        var map = new LinkedHashMap<String, Object>();
        map.put("name", property.getName());
        map.put("status", property.getStatus().name());
        if (property.getCity() != null) {
            map.put("city", property.getCity());
        }
        return map;
    }

    public Property getPropertyOrThrow(UUID id) {
        var agencyId = TenantContext.requireAgencyId();
        return propertyRepository.findByIdAndAgencyId(id, agencyId)
                .orElseThrow(() -> new NotFoundException("error.property.not_found"));
    }
}
