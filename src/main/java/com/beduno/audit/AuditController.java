package com.beduno.audit;

import com.beduno.audit.dto.AuditEventResponse;
import com.beduno.common.model.PageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Tag(name = "Audit", description = "Immutable audit trail of all changes")
@RestController
@RequestMapping("/api/v1/audit")
@RequiredArgsConstructor
public class AuditController {

    private final AuditService auditService;

    @Operation(summary = "Query audit events",
            description = "Paginated audit trail filterable by entity type, entity ID, actor user, and date range")
    @GetMapping
    @PreAuthorize("hasAnyRole('AGENCY_ADMIN', 'AGENCY_PLANNER')")
    public ResponseEntity<PageResponse<AuditEventResponse>> findAll(
            @RequestParam(required = false) AuditEntityType entityType,
            @RequestParam(required = false) UUID entityId,
            @RequestParam(required = false) UUID actorUserId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant dateFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant dateTo,
            @PageableDefault(sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {
        return ResponseEntity.ok(auditService.findAll(entityType, entityId, actorUserId, dateFrom, dateTo, pageable));
    }

    @Operation(summary = "Get an audit event", description = "Fetches a single audit event by id")
    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('AGENCY_ADMIN', 'AGENCY_PLANNER')")
    public ResponseEntity<AuditEventResponse> findById(@PathVariable UUID id) {
        return ResponseEntity.ok(auditService.findById(id));
    }

    @Operation(summary = "Recent events for an entity", description = "Last 5 audit events for an entity, newest first")
    @GetMapping("/recent")
    @PreAuthorize("hasAnyRole('AGENCY_ADMIN', 'AGENCY_PLANNER')")
    public ResponseEntity<List<AuditEventResponse>> findRecentForEntity(@RequestParam UUID entityId) {
        return ResponseEntity.ok(auditService.findRecentForEntity(entityId));
    }
}
