package com.beduno.common.security;

import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Optional;
import java.util.UUID;

/**
 * The caller behind the current request.
 *
 * <p>Two things lived in five copies before this existed: {@code currentUserId()}, pasted verbatim
 * into StayService, BedService, RoomService, PropertyService and WorkerService, and the
 * property-scope check, pasted into three controllers. Both are security-relevant, and a rule with
 * five copies is a rule that gets fixed in four of them.
 */
public final class SecurityUtils {

    private SecurityUtils() {
    }

    public static Optional<CurrentUser> currentUser() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof CurrentUser currentUser) {
            return Optional.of(currentUser);
        }
        return Optional.empty();
    }

    /**
     * The caller's user id, or null when there is no authenticated principal -- which is the case
     * for the scheduler sweep and the startup runners, whose audit entries carry no actor.
     */
    public static UUID currentUserId() {
        return currentUser().map(CurrentUser::userId).orElse(null);
    }

    /**
     * Throws {@link com.beduno.common.exception.ForbiddenException} unless the caller may act on
     * this property. A no-op for AGENCY_ADMIN and AGENCY_PLANNER, who are agency-wide by
     * definition, and for unauthenticated internal callers, who have no principal to scope.
     */
    public static void requirePropertyAccess(UUID propertyId) {
        currentUser().ifPresent(user -> user.requirePropertyAccess(propertyId));
    }
}
