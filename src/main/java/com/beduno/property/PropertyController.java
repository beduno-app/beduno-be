package com.beduno.property;

import com.beduno.common.model.PageResponse;
import com.beduno.common.security.CurrentUser;
import com.beduno.property.dto.CreatePropertyRequest;
import com.beduno.property.dto.PropertyResponse;
import com.beduno.property.dto.UpdatePropertyRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
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

@Tag(name = "Properties", description = "Property management")
@RestController
@RequestMapping("/api/v1/properties")
@RequiredArgsConstructor
public class PropertyController {

    private final PropertyService propertyService;

    @Operation(summary = "List properties", description = "Paginated list with optional status and name filters")
    @GetMapping
    @PreAuthorize("hasAnyRole('AGENCY_ADMIN', 'AGENCY_PLANNER', 'PROPERTY_ADMIN', 'FRONT_DESK')")
    public ResponseEntity<PageResponse<PropertyResponse>> findAll(
            @RequestParam(required = false) PropertyStatus status,
            @RequestParam(required = false) String search,
            @PageableDefault(sort = "name", direction = Sort.Direction.ASC) Pageable pageable) {
        return ResponseEntity.ok(propertyService.findAll(status, search, pageable));
    }

    @Operation(summary = "Get property by ID")
    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('AGENCY_ADMIN', 'AGENCY_PLANNER', 'PROPERTY_ADMIN', 'FRONT_DESK')")
    public ResponseEntity<PropertyResponse> findById(@PathVariable UUID id) {
        return ResponseEntity.ok(propertyService.findById(id));
    }

    @Operation(summary = "Create property")
    @ApiResponse(responseCode = "201", description = "Property created")
    @PostMapping
    @PreAuthorize("hasRole('AGENCY_ADMIN')")
    public ResponseEntity<PropertyResponse> create(@Valid @RequestBody CreatePropertyRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(propertyService.create(request));
    }

    @Operation(summary = "Update property")
    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('AGENCY_ADMIN', 'PROPERTY_ADMIN')")
    public ResponseEntity<PropertyResponse> update(@PathVariable UUID id,
                                                    @Valid @RequestBody UpdatePropertyRequest request,
                                                    @AuthenticationPrincipal CurrentUser currentUser) {
        if (currentUser.role() == com.beduno.user.Role.PROPERTY_ADMIN) {
            if (!currentUser.hasPropertyAccess(id)) {
                throw new com.beduno.common.exception.ForbiddenException("error.property.access_denied");
            }
        }
        return ResponseEntity.ok(propertyService.update(id, request));
    }

    @Operation(summary = "Delete property", description = "Soft-deletes the property")
    @ApiResponse(responseCode = "204", description = "Property deleted")
    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('AGENCY_ADMIN')")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        propertyService.delete(id);
        return ResponseEntity.noContent().build();
    }
}
