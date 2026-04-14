package com.bedok.property;

import com.bedok.common.model.PageResponse;
import com.bedok.common.security.CurrentUser;
import com.bedok.property.dto.CreatePropertyRequest;
import com.bedok.property.dto.PropertyResponse;
import com.bedok.property.dto.UpdatePropertyRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/properties")
@RequiredArgsConstructor
public class PropertyController {

    private final PropertyService propertyService;

    @GetMapping
    @PreAuthorize("hasAnyRole('AGENCY_ADMIN', 'AGENCY_PLANNER', 'PROPERTY_ADMIN', 'FRONT_DESK')")
    public ResponseEntity<PageResponse<PropertyResponse>> findAll(
            @RequestParam(required = false) PropertyStatus status,
            @RequestParam(required = false) String search,
            @PageableDefault(sort = "name", direction = Sort.Direction.ASC) Pageable pageable) {
        return ResponseEntity.ok(propertyService.findAll(status, search, pageable));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('AGENCY_ADMIN', 'AGENCY_PLANNER', 'PROPERTY_ADMIN', 'FRONT_DESK')")
    public ResponseEntity<PropertyResponse> findById(@PathVariable UUID id) {
        return ResponseEntity.ok(propertyService.findById(id));
    }

    @PostMapping
    @PreAuthorize("hasRole('AGENCY_ADMIN')")
    public ResponseEntity<PropertyResponse> create(@Valid @RequestBody CreatePropertyRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(propertyService.create(request));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('AGENCY_ADMIN', 'PROPERTY_ADMIN')")
    public ResponseEntity<PropertyResponse> update(@PathVariable UUID id,
                                                    @Valid @RequestBody UpdatePropertyRequest request,
                                                    @AuthenticationPrincipal CurrentUser currentUser) {
        if (currentUser.role() == com.bedok.user.Role.PROPERTY_ADMIN) {
            if (!currentUser.hasPropertyAccess(id)) {
                throw new com.bedok.common.exception.ForbiddenException("error.property.access_denied");
            }
        }
        return ResponseEntity.ok(propertyService.update(id, request));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('AGENCY_ADMIN')")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        propertyService.delete(id);
        return ResponseEntity.noContent().build();
    }
}
