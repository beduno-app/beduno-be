package com.beduno.audit;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AuditRepository extends JpaRepository<AuditEvent, UUID> {

    Optional<AuditEvent> findByIdAndAgencyId(UUID id, UUID agencyId);

    List<AuditEvent> findTop5ByAgencyIdAndEntityIdOrderByCreatedAtDesc(UUID agencyId, UUID entityId);

    /**
     * Deliberately unordered. A Pageable's sort is appended as its own {@code order by}, so a
     * hardcoded one here produced a query with two of them -- a syntax error that made every
     * request to this endpoint a 500, the default sort included. The order comes from the
     * controller's @PageableDefault, which asks for createdAt descending.
     */
    @Query("""
            SELECT e FROM AuditEvent e
            WHERE e.agencyId = :agencyId
              AND (CAST(:entityType AS STRING) IS NULL OR e.entityType = :entityType)
              AND (CAST(:excludeEntityType AS STRING) IS NULL OR e.entityType <> :excludeEntityType)
              AND (CAST(:entityId AS java.util.UUID) IS NULL OR e.entityId = :entityId)
              AND (CAST(:actorUserId AS java.util.UUID) IS NULL OR e.actorUserId = :actorUserId)
              AND (CAST(:dateFrom AS java.time.Instant) IS NULL OR e.createdAt >= :dateFrom)
              AND (CAST(:dateTo AS java.time.Instant) IS NULL OR e.createdAt <= :dateTo)
            """)
    Page<AuditEvent> findAllWithFilters(
            @Param("agencyId") UUID agencyId,
            @Param("entityType") AuditEntityType entityType,
            @Param("excludeEntityType") AuditEntityType excludeEntityType,
            @Param("entityId") UUID entityId,
            @Param("actorUserId") UUID actorUserId,
            @Param("dateFrom") Instant dateFrom,
            @Param("dateTo") Instant dateTo,
            Pageable pageable);
}
