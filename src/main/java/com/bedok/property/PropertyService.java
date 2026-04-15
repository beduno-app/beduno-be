package com.bedok.property;

import com.bedok.audit.AuditAction;
import com.bedok.audit.AuditEntityType;
import com.bedok.audit.AuditService;
import com.bedok.common.exception.NotFoundException;
import com.bedok.common.model.PageResponse;
import com.bedok.common.security.CurrentUser;
import com.bedok.common.security.TenantContext;
import com.bedok.property.dto.CreatePropertyRequest;
import com.bedok.property.dto.PropertyResponse;
import com.bedok.property.dto.UpdatePropertyRequest;
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
public class PropertyService {

    private final PropertyRepository propertyRepository;
    private final PropertyMapper propertyMapper;
    private final AuditService auditService;

    @Transactional(readOnly = true)
    public PageResponse<PropertyResponse> findAll(PropertyStatus status, String search, Pageable pageable) {
        var agencyId = TenantContext.requireAgencyId();
        var page = propertyRepository.findAllByAgencyIdWithFilters(
                agencyId,
                status != null ? status.name() : null,
                search, pageable);
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
        auditService.log(agencyId, currentUserId(), AuditEntityType.PROPERTY, property.getId(),
                AuditAction.CREATED, null, snapshot(property), null);
        return propertyMapper.toResponse(property);
    }

    @Transactional
    public PropertyResponse update(UUID id, UpdatePropertyRequest request) {
        var property = getPropertyOrThrow(id);
        var previous = snapshot(property);
        propertyMapper.updateEntity(request, property);
        property = propertyRepository.save(property);
        auditService.log(property.getAgencyId(), currentUserId(), AuditEntityType.PROPERTY, property.getId(),
                AuditAction.UPDATED, previous, snapshot(property), null);
        return propertyMapper.toResponse(property);
    }

    @Transactional
    public void delete(UUID id) {
        var property = getPropertyOrThrow(id);
        var previous = snapshot(property);
        propertyRepository.delete(property);
        auditService.log(property.getAgencyId(), currentUserId(), AuditEntityType.PROPERTY, property.getId(),
                AuditAction.DELETED, previous, null, null);
    }

    private UUID currentUserId() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof CurrentUser currentUser) {
            return currentUser.userId();
        }
        return null;
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
