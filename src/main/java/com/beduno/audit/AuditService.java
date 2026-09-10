package com.beduno.audit;

import com.beduno.common.model.SortFields;
import com.beduno.audit.dto.AuditEventResponse;
import com.beduno.common.model.PageResponse;
import com.beduno.common.security.TenantContext;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AuditService {

    /**
     * Audit events come from a JPQL query, so these map to entity properties rather than columns.
     */
    private static final Map<String, String> SORTABLE = Map.of(
            "createdAt", "createdAt",
            "entityType", "entityType",
            "entityId", "entityId",
            "action", "action",
            "actorUserId", "actorUserId");

    private final AuditRepository auditRepository;
    private final AuditMapper auditMapper;

    @Transactional
    public void log(UUID agencyId, UUID actorUserId, AuditEntityType entityType, UUID entityId,
                    AuditAction action, Map<String, Object> previousState, Map<String, Object> newState,
                    String reason) {
        var event = new AuditEvent();
        event.setAgencyId(agencyId);
        event.setActorUserId(actorUserId);
        event.setEntityType(entityType);
        event.setEntityId(entityId);
        event.setAction(action);
        event.setPreviousState(previousState);
        event.setNewState(newState);
        event.setReason(reason);
        auditRepository.save(event);
    }

    @Transactional(readOnly = true)
    public PageResponse<AuditEventResponse> findAll(
            AuditEntityType entityType, UUID entityId, UUID actorUserId,
            Instant dateFrom, Instant dateTo, Pageable pageable) {
        var agencyId = TenantContext.requireAgencyId();
        var page = auditRepository.findAllWithFilters(
                agencyId, entityType, entityId, actorUserId, dateFrom, dateTo,
                SortFields.translate(pageable, SORTABLE));
        return PageResponse.of(page.map(auditMapper::toResponse));
    }
}
