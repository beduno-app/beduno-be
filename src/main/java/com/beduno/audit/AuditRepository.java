package com.beduno.audit;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.UUID;

public interface AuditRepository extends JpaRepository<AuditEvent, UUID> {

    @Query("""
            SELECT e FROM AuditEvent e
            WHERE e.agencyId = :agencyId
              AND (:entityType IS NULL OR e.entityType = :entityType)
              AND (:entityId IS NULL OR e.entityId = :entityId)
              AND (:actorUserId IS NULL OR e.actorUserId = :actorUserId)
              AND (:dateFrom IS NULL OR e.createdAt >= :dateFrom)
              AND (:dateTo IS NULL OR e.createdAt <= :dateTo)
            ORDER BY e.createdAt DESC
            """)
    Page<AuditEvent> findAllWithFilters(
            @Param("agencyId") UUID agencyId,
            @Param("entityType") AuditEntityType entityType,
            @Param("entityId") UUID entityId,
            @Param("actorUserId") UUID actorUserId,
            @Param("dateFrom") Instant dateFrom,
            @Param("dateTo") Instant dateTo,
            Pageable pageable);
}
