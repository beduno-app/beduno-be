package com.beduno.common.security;

import java.util.UUID;

public final class TenantContext {

    private static final ThreadLocal<UUID> CURRENT_AGENCY_ID = new ThreadLocal<>();

    private TenantContext() {}

    public static void setAgencyId(UUID agencyId) {
        CURRENT_AGENCY_ID.set(agencyId);
    }

    public static UUID getAgencyId() {
        return CURRENT_AGENCY_ID.get();
    }

    public static UUID requireAgencyId() {
        var id = CURRENT_AGENCY_ID.get();
        if (id == null) {
            throw new IllegalStateException("No tenant context set");
        }
        return id;
    }

    public static void clear() {
        CURRENT_AGENCY_ID.remove();
    }
}
