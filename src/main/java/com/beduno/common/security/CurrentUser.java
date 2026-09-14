package com.beduno.common.security;

import com.beduno.common.exception.ForbiddenException;
import com.beduno.user.Role;

import java.util.UUID;

public record CurrentUser(
        UUID userId,
        UUID agencyId,
        Role role,
        UUID[] assignedPropertyIds,
        String language
) {

    /**
     * Throws unless this caller may act on the property. The three-line equivalent was copy-pasted
     * into PropertyController, RoomController and BedController, one of them with inline FQCNs;
     * having one copy is what makes it possible to extend scoping to the remaining modules without
     * leaving a fourth variant behind.
     */
    public void requirePropertyAccess(UUID propertyId) {
        if (!hasPropertyAccess(propertyId)) {
            throw new ForbiddenException("error.property.access_denied");
        }
    }

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
