package com.beduno.bed;

import java.util.List;
import java.util.UUID;

import com.beduno.bed.dto.BedResponse;
import com.beduno.bed.dto.BulkGenerateBedsRequest;
import com.beduno.bed.dto.CreateBedRequest;
import com.beduno.bed.dto.UpdateBedRequest;
import com.beduno.common.exception.ForbiddenException;
import com.beduno.common.security.CurrentUser;
import com.beduno.user.Role;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
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
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Beds", description = "Bed management nested under a room")
@RestController
@RequestMapping("/api/v1/properties/{propertyId}/rooms/{roomId}/beds")
@RequiredArgsConstructor
public class BedController {

    private final BedService bedService;

    @Operation(summary = "List beds in a room")
    @GetMapping
    @PreAuthorize("hasAnyRole('AGENCY_ADMIN', 'AGENCY_PLANNER', 'PROPERTY_ADMIN', 'FRONT_DESK')")
    public ResponseEntity<List<BedResponse>> findAll(@PathVariable UUID propertyId,
                                                       @PathVariable UUID roomId) {
        return ResponseEntity.ok(bedService.findAllByRoomId(propertyId, roomId));
    }

    @Operation(summary = "Get bed by ID")
    @GetMapping("/{bedId}")
    @PreAuthorize("hasAnyRole('AGENCY_ADMIN', 'AGENCY_PLANNER', 'PROPERTY_ADMIN', 'FRONT_DESK')")
    public ResponseEntity<BedResponse> findById(@PathVariable UUID propertyId,
                                                 @PathVariable UUID roomId,
                                                 @PathVariable UUID bedId) {
        return ResponseEntity.ok(bedService.findById(propertyId, roomId, bedId));
    }

    @Operation(summary = "Create a single bed")
    @ApiResponse(responseCode = "201", description = "Bed created")
    @PostMapping
    @PreAuthorize("hasAnyRole('AGENCY_ADMIN', 'PROPERTY_ADMIN')")
    public ResponseEntity<BedResponse> create(@PathVariable UUID propertyId,
                                               @PathVariable UUID roomId,
                                               @Valid @RequestBody CreateBedRequest request,
                                               @AuthenticationPrincipal CurrentUser currentUser) {
        checkPropertyAccess(currentUser, propertyId);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(bedService.create(propertyId, roomId, request));
    }

    @Operation(summary = "Bulk-generate beds",
            description = "Adds count sequentially numbered beds after the room's current highest numeric label")
    @ApiResponse(responseCode = "201", description = "Beds created")
    @PostMapping("/bulk-generate")
    @PreAuthorize("hasAnyRole('AGENCY_ADMIN', 'PROPERTY_ADMIN')")
    public ResponseEntity<List<BedResponse>> bulkGenerate(@PathVariable UUID propertyId,
                                                            @PathVariable UUID roomId,
                                                            @Valid @RequestBody BulkGenerateBedsRequest request,
                                                            @AuthenticationPrincipal CurrentUser currentUser) {
        checkPropertyAccess(currentUser, propertyId);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(bedService.bulkGenerate(propertyId, roomId, request.count()));
    }

    @Operation(summary = "Update bed (rename or change status)")
    @PutMapping("/{bedId}")
    @PreAuthorize("hasAnyRole('AGENCY_ADMIN', 'PROPERTY_ADMIN')")
    public ResponseEntity<BedResponse> update(@PathVariable UUID propertyId,
                                               @PathVariable UUID roomId,
                                               @PathVariable UUID bedId,
                                               @Valid @RequestBody UpdateBedRequest request,
                                               @AuthenticationPrincipal CurrentUser currentUser) {
        checkPropertyAccess(currentUser, propertyId);
        return ResponseEntity.ok(bedService.update(propertyId, roomId, bedId, request));
    }

    @Operation(summary = "Delete bed",
            description = "Permanently deletes the bed.")
    @ApiResponse(responseCode = "204", description = "Bed deleted")
    @DeleteMapping("/{bedId}")
    @PreAuthorize("hasRole('AGENCY_ADMIN')")
    public ResponseEntity<Void> delete(@PathVariable UUID propertyId,
                                        @PathVariable UUID roomId,
                                        @PathVariable UUID bedId) {
        bedService.delete(propertyId, roomId, bedId);
        return ResponseEntity.noContent().build();
    }

    private void checkPropertyAccess(CurrentUser currentUser, UUID propertyId) {
        if (currentUser.role() == Role.PROPERTY_ADMIN && !currentUser.hasPropertyAccess(propertyId)) {
            throw new ForbiddenException("error.property.access_denied");
        }
    }
}
