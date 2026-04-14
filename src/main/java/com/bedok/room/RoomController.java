package com.bedok.room;

import com.bedok.common.exception.ForbiddenException;
import com.bedok.common.model.PageResponse;
import com.bedok.common.security.CurrentUser;
import com.bedok.room.dto.CreateRoomRequest;
import com.bedok.room.dto.RoomResponse;
import com.bedok.room.dto.UpdateRoomRequest;
import com.bedok.user.Role;
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
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/properties/{propertyId}/rooms")
@RequiredArgsConstructor
public class RoomController {

    private final RoomService roomService;

    @GetMapping
    @PreAuthorize("hasAnyRole('AGENCY_ADMIN', 'AGENCY_PLANNER', 'PROPERTY_ADMIN', 'FRONT_DESK')")
    public ResponseEntity<PageResponse<RoomResponse>> findAll(
            @PathVariable UUID propertyId,
            @PageableDefault(sort = "name", direction = Sort.Direction.ASC) Pageable pageable) {
        return ResponseEntity.ok(roomService.findAllByPropertyId(propertyId, pageable));
    }

    @GetMapping("/{roomId}")
    @PreAuthorize("hasAnyRole('AGENCY_ADMIN', 'AGENCY_PLANNER', 'PROPERTY_ADMIN', 'FRONT_DESK')")
    public ResponseEntity<RoomResponse> findById(@PathVariable UUID propertyId,
                                                  @PathVariable UUID roomId) {
        return ResponseEntity.ok(roomService.findById(propertyId, roomId));
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('AGENCY_ADMIN', 'PROPERTY_ADMIN')")
    public ResponseEntity<RoomResponse> create(@PathVariable UUID propertyId,
                                                @Valid @RequestBody CreateRoomRequest request,
                                                @AuthenticationPrincipal CurrentUser currentUser) {
        checkPropertyAccess(currentUser, propertyId);
        return ResponseEntity.status(HttpStatus.CREATED).body(roomService.create(propertyId, request));
    }

    @PutMapping("/{roomId}")
    @PreAuthorize("hasAnyRole('AGENCY_ADMIN', 'PROPERTY_ADMIN')")
    public ResponseEntity<RoomResponse> update(@PathVariable UUID propertyId,
                                                @PathVariable UUID roomId,
                                                @Valid @RequestBody UpdateRoomRequest request,
                                                @AuthenticationPrincipal CurrentUser currentUser) {
        checkPropertyAccess(currentUser, propertyId);
        return ResponseEntity.ok(roomService.update(propertyId, roomId, request));
    }

    @DeleteMapping("/{roomId}")
    @PreAuthorize("hasRole('AGENCY_ADMIN')")
    public ResponseEntity<Void> delete(@PathVariable UUID propertyId,
                                        @PathVariable UUID roomId) {
        roomService.delete(propertyId, roomId);
        return ResponseEntity.noContent().build();
    }

    private void checkPropertyAccess(CurrentUser currentUser, UUID propertyId) {
        if (currentUser.role() == Role.PROPERTY_ADMIN && !currentUser.hasPropertyAccess(propertyId)) {
            throw new ForbiddenException("error.property.access_denied");
        }
    }
}
