package com.beduno.user;

import com.beduno.common.model.PageResponse;
import com.beduno.user.dto.CreateUserRequest;
import com.beduno.user.dto.UpdateUserRequest;
import com.beduno.user.dto.UserResponse;
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

/**
 * Agency-admin-only user management. Every endpoint here is gated to AGENCY_ADMIN: unlike workers
 * or stays, this surface exposes email addresses and role assignments for every account in the
 * agency, so the broader FRONT_DESK/PROPERTY_ADMIN read access other modules grant does not apply.
 */
@Tag(name = "Users", description = "Agency user management (admin-only)")
@RestController
@RequestMapping("/api/v1/users")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

    @Operation(summary = "List users", description = "Paginated list with optional filters: role, status, name/email search")
    @GetMapping
    @PreAuthorize("hasRole('AGENCY_ADMIN')")
    public ResponseEntity<PageResponse<UserResponse>> findAll(
            @RequestParam(required = false) Role role,
            @RequestParam(required = false) UserStatus status,
            @RequestParam(required = false) String search,
            @PageableDefault(sort = "lastName", direction = Sort.Direction.ASC) Pageable pageable) {
        return ResponseEntity.ok(userService.findAll(role, status, search, pageable));
    }

    @Operation(summary = "Get user by ID")
    @GetMapping("/{id}")
    @PreAuthorize("hasRole('AGENCY_ADMIN')")
    public ResponseEntity<UserResponse> findById(@PathVariable UUID id) {
        return ResponseEntity.ok(userService.findById(id));
    }

    @Operation(summary = "Create user")
    @ApiResponse(responseCode = "201", description = "User created")
    @ApiResponse(responseCode = "409", description = "Email already in use")
    @PostMapping
    @PreAuthorize("hasRole('AGENCY_ADMIN')")
    public ResponseEntity<UserResponse> create(@Valid @RequestBody CreateUserRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(userService.create(request));
    }

    @Operation(summary = "Update user", description = "Email, name, role, language, assigned properties, and status")
    @ApiResponse(responseCode = "409", description = "Email already in use, or this is the agency's last active admin")
    @PutMapping("/{id}")
    @PreAuthorize("hasRole('AGENCY_ADMIN')")
    public ResponseEntity<UserResponse> update(@PathVariable UUID id,
                                                @Valid @RequestBody UpdateUserRequest request) {
        return ResponseEntity.ok(userService.update(id, request));
    }

    @Operation(summary = "Deactivate user",
            description = "Sets status to INACTIVE. Never a hard delete: users are referenced by history (e.g. confirmed stays).")
    @ApiResponse(responseCode = "204", description = "User deactivated")
    @ApiResponse(responseCode = "409", description = "Cannot deactivate yourself, or the agency's last active admin")
    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('AGENCY_ADMIN')")
    public ResponseEntity<Void> deactivate(@PathVariable UUID id) {
        userService.deactivate(id);
        return ResponseEntity.noContent().build();
    }
}
