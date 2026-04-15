package com.bedok.audit.dto;

import com.bedok.audit.AuditAction;
import com.bedok.audit.AuditEntityType;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record AuditEventResponse(
        UUID id,
        AuditEntityType entityType,
        UUID entityId,
        AuditAction action,
        UUID actorUserId,
        Map<String, Object> previousState,
        Map<String, Object> newState,
        String reason,
        Instant createdAt
) {}
