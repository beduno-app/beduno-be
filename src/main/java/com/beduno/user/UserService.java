package com.beduno.user;

import com.beduno.audit.AuditAction;
import com.beduno.audit.AuditEntityType;
import com.beduno.audit.AuditService;
import com.beduno.common.exception.ConflictException;
import com.beduno.common.exception.NotFoundException;
import com.beduno.common.model.PageResponse;
import com.beduno.common.model.SortFields;
import com.beduno.common.security.CurrentUser;
import com.beduno.common.security.TenantContext;
import com.beduno.user.dto.CreateUserRequest;
import com.beduno.user.dto.UpdateUserRequest;
import com.beduno.user.dto.UserResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class UserService {

    private static final Map<String, String> SORTABLE = Map.of(
            "firstName", "first_name",
            "lastName", "last_name",
            "email", "email",
            "role", "role",
            "status", "status",
            "createdAt", "created_at",
            "updatedAt", "updated_at");

    private final UserRepository userRepository;
    private final UserMapper userMapper;
    private final AuditService auditService;
    private final PasswordEncoder passwordEncoder;

    @Transactional(readOnly = true)
    public PageResponse<UserResponse> findAll(Role role, UserStatus status, String search, Pageable pageable) {
        var agencyId = TenantContext.requireAgencyId();
        var sorted = SortFields.translate(pageable, SORTABLE);
        var page = userRepository.findAllByAgencyIdWithFilters(
                agencyId,
                role != null ? role.name() : null,
                status != null ? status.name() : null,
                search, sorted);
        return PageResponse.of(page.map(userMapper::toResponse));
    }

    @Transactional(readOnly = true)
    public UserResponse findById(UUID id) {
        return userMapper.toResponse(getUserOrThrow(id));
    }

    @Transactional
    public UserResponse create(CreateUserRequest request) {
        var agencyId = TenantContext.requireAgencyId();

        // Email is unique across the whole table (V8__unique_user_email.sql), not just within the
        // agency: login resolves a user by email alone, with no agency selector for a caller to
        // pass, so two agencies sharing an address would make login pick between them arbitrarily.
        if (userRepository.existsByEmail(request.email())) {
            throw new ConflictException("error.user.email_exists");
        }

        var user = userMapper.toEntity(request);
        user.setAgencyId(agencyId);
        user.setPasswordHash(passwordEncoder.encode(request.password()));
        user.setStatus(UserStatus.ACTIVE);
        user = userRepository.save(user);
        auditService.log(agencyId, currentUserId(), AuditEntityType.USER, user.getId(),
                AuditAction.CREATED, null, snapshot(user), null);
        return userMapper.toResponse(user);
    }

    @Transactional
    public UserResponse update(UUID id, UpdateUserRequest request) {
        var user = getUserOrThrow(id);
        var previous = snapshot(user);

        if (userRepository.existsByEmailAndIdNot(request.email(), id)) {
            throw new ConflictException("error.user.email_exists");
        }

        // The same lock-out guard deactivate() enforces. Without it, PUT is a way around
        // DELETE: an admin could deactivate their own account and lose access on the spot.
        if (request.status() == UserStatus.INACTIVE && user.getId().equals(currentUserId())) {
            throw new ConflictException("error.user.cannot_deactivate_self");
        }

        var losingAdminCoverage = user.getRole() == Role.AGENCY_ADMIN
                && user.getStatus() == UserStatus.ACTIVE
                && (request.role() != Role.AGENCY_ADMIN || request.status() != UserStatus.ACTIVE);
        if (losingAdminCoverage) {
            assertAnotherActiveAdminExists(user);
        }

        userMapper.updateEntity(request, user);
        user = userRepository.save(user);
        auditService.log(user.getAgencyId(), currentUserId(), AuditEntityType.USER, user.getId(),
                AuditAction.UPDATED, previous, snapshot(user), null);
        return userMapper.toResponse(user);
    }

    /**
     * Deactivates rather than deletes: {@code stays.confirmed_by_user_id} references {@code
     * users(id)} with an implicit RESTRICT FK, and a user is never a record with no history the
     * way a freshly-created worker can be. Status is the reversible, audit-friendly analogue.
     */
    @Transactional
    public void deactivate(UUID id) {
        var user = getUserOrThrow(id);

        if (user.getId().equals(currentUserId())) {
            throw new ConflictException("error.user.cannot_deactivate_self");
        }
        if (user.getStatus() == UserStatus.INACTIVE) {
            return;
        }
        if (user.getRole() == Role.AGENCY_ADMIN) {
            assertAnotherActiveAdminExists(user);
        }

        var previous = snapshot(user);
        user.setStatus(UserStatus.INACTIVE);
        user = userRepository.save(user);
        auditService.log(user.getAgencyId(), currentUserId(), AuditEntityType.USER, user.getId(),
                AuditAction.UPDATED, previous, snapshot(user), "deactivated");
    }

    private void assertAnotherActiveAdminExists(User user) {
        var anotherActiveAdminExists = userRepository.existsByAgencyIdAndRoleAndStatusAndIdNot(
                user.getAgencyId(), Role.AGENCY_ADMIN, UserStatus.ACTIVE, user.getId());
        if (!anotherActiveAdminExists) {
            throw new ConflictException("error.user.last_admin");
        }
    }

    private UUID currentUserId() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof CurrentUser currentUser) {
            return currentUser.userId();
        }
        return null;
    }

    private Map<String, Object> snapshot(User user) {
        var map = new LinkedHashMap<String, Object>();
        map.put("email", user.getEmail());
        map.put("firstName", user.getFirstName());
        map.put("lastName", user.getLastName());
        map.put("role", user.getRole().name());
        map.put("status", user.getStatus().name());
        return map;
    }

    private User getUserOrThrow(UUID id) {
        var agencyId = TenantContext.requireAgencyId();
        return userRepository.findByIdAndAgencyId(id, agencyId)
                .orElseThrow(() -> new NotFoundException("error.user.not_found"));
    }
}
