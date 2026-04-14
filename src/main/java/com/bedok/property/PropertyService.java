package com.bedok.property;

import com.bedok.common.exception.NotFoundException;
import com.bedok.common.model.PageResponse;
import com.bedok.common.security.TenantContext;
import com.bedok.property.dto.CreatePropertyRequest;
import com.bedok.property.dto.PropertyResponse;
import com.bedok.property.dto.UpdatePropertyRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class PropertyService {

    private final PropertyRepository propertyRepository;
    private final PropertyMapper propertyMapper;

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
        return propertyMapper.toResponse(property);
    }

    @Transactional
    public PropertyResponse update(UUID id, UpdatePropertyRequest request) {
        var property = getPropertyOrThrow(id);
        propertyMapper.updateEntity(request, property);
        property = propertyRepository.save(property);
        return propertyMapper.toResponse(property);
    }

    @Transactional
    public void delete(UUID id) {
        var property = getPropertyOrThrow(id);
        propertyRepository.delete(property);
    }

    public Property getPropertyOrThrow(UUID id) {
        var agencyId = TenantContext.requireAgencyId();
        return propertyRepository.findByIdAndAgencyId(id, agencyId)
                .orElseThrow(() -> new NotFoundException("error.property.not_found"));
    }
}
