package com.beduno.audit.dto;

import com.beduno.audit.AuditAction;
import com.beduno.audit.AuditEntityType;

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
