package com.beduno.common.security;

import com.beduno.user.Role;

import java.util.UUID;

public record CurrentUser(
        UUID userId,
        UUID agencyId,
        Role role,
        UUID[] assignedPropertyIds,
        String language
) {

    public boolean hasPropertyAccess(UUID propertyId) {
        if (role == Role.AGENCY_ADMIN || role == Role.AGENCY_PLANNER) {
            return true;
        }
        if (assignedPropertyIds == null) {
            return false;
        }
        for (UUID id : assignedPropertyIds) {
            if (id.equals(propertyId)) {
                return true;
            }
        }
        return false;
    }
}
