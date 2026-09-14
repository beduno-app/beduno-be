package com.beduno.stay;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface StayRepository extends JpaRepository<Stay, UUID> {

    Optional<Stay> findByIdAndAgencyId(UUID id, UUID agencyId);

    /**
     * Counts every stay referencing a property, terminal ones included. Deleting a
     * property is blocked while any row references it, because stays.property_id is
     * a RESTRICT foreign key and the delete would otherwise fail at the database.
     */
    long countByAgencyIdAndPropertyId(UUID agencyId, UUID propertyId);

    /** Counts every stay referencing a room, for the same reason as above. */
    long countByAgencyIdAndRoomId(UUID agencyId, UUID roomId);

    /** Counts every stay referencing a bed, for the same reason as above -- stays.bed_id is a RESTRICT foreign key. */
    long countByAgencyIdAndBedId(UUID agencyId, UUID bedId);

    @Query(value = """
            SELECT * FROM stays s
            WHERE s.agency_id = :agencyId
            AND (CAST(:workerId AS uuid) IS NULL OR s.worker_id = CAST(:workerId AS uuid))
            AND (CAST(:propertyId AS uuid) IS NULL OR s.property_id = CAST(:propertyId AS uuid))
            AND (CAST(:status AS VARCHAR) IS NULL OR s.status = CAST(:status AS VARCHAR))
            AND (CAST(:dateFrom AS DATE) IS NULL OR s.date_to IS NULL OR s.date_to >= CAST(:dateFrom AS DATE))
            AND (CAST(:dateTo AS DATE) IS NULL OR s.date_from <= CAST(:dateTo AS DATE))
            """,
            countQuery = """
            SELECT COUNT(*) FROM stays s
            WHERE s.agency_id = :agencyId
            AND (CAST(:workerId AS uuid) IS NULL OR s.worker_id = CAST(:workerId AS uuid))
            AND (CAST(:propertyId AS uuid) IS NULL OR s.property_id = CAST(:propertyId AS uuid))
            AND (CAST(:status AS VARCHAR) IS NULL OR s.status = CAST(:status AS VARCHAR))
            AND (CAST(:dateFrom AS DATE) IS NULL OR s.date_to IS NULL OR s.date_to >= CAST(:dateFrom AS DATE))
            AND (CAST(:dateTo AS DATE) IS NULL OR s.date_from <= CAST(:dateTo AS DATE))
            """,
            nativeQuery = true)
    Page<Stay> findAllWithFilters(
            @Param("agencyId") UUID agencyId,
            @Param("workerId") UUID workerId,
            @Param("propertyId") UUID propertyId,
            @Param("status") String status,
            @Param("dateFrom") LocalDate dateFrom,
            @Param("dateTo") LocalDate dateTo,
            Pageable pageable
    );

    /**
     * Half-open overlap between the candidate period [dateFrom, effectiveDateTo) and an existing
     * stay. Callers must pass {@link StayDates#effectiveEnd(LocalDate)} rather than the raw
     * dateTo, so that an open-ended candidate is represented by a date PostgreSQL can actually
     * bind. A null-aware predicate is not an option here: HQL renders {@code :p IS NULL} as a
     * bare {@code ? IS NULL}, which PostgreSQL rejects with "could not determine data type".
     *
     * <p>A CHECKED_IN stay occupies its bed until at least {@code today}, whatever its planned
     * dateTo says: a worker whose contract was extended without anyone updating the stay is still
     * physically in the bed, and offering it to somebody else put two people in it. It does not
     * occupy beyond today -- a booking that starts after today is only blocked by the stay's own
     * planned dates, since the overstay is expected to be resolved rather than to run forever.
     */
    @Query("""
            SELECT COUNT(s) FROM Stay s
            WHERE s.bedId = :bedId
              AND s.agencyId = :agencyId
              AND s.status IN :statuses
              AND s.dateFrom < :effectiveDateTo
              AND (s.dateTo IS NULL OR s.dateTo > :dateFrom
                   OR (s.status = com.beduno.stay.StayStatus.CHECKED_IN AND :today >= :dateFrom))
            """)
    long countActiveStaysInBed(
            @Param("bedId") UUID bedId,
            @Param("agencyId") UUID agencyId,
            @Param("dateFrom") LocalDate dateFrom,
            @Param("effectiveDateTo") LocalDate effectiveDateTo,
            @Param("today") LocalDate today,
            @Param("statuses") List<StayStatus> statuses
    );

    /** As {@link #countActiveStaysInBed}, ignoring the stay being updated. */
    @Query("""
            SELECT COUNT(s) FROM Stay s
            WHERE s.bedId = :bedId
              AND s.agencyId = :agencyId
              AND s.status IN :statuses
              AND s.dateFrom < :effectiveDateTo
              AND (s.dateTo IS NULL OR s.dateTo > :dateFrom
                   OR (s.status = com.beduno.stay.StayStatus.CHECKED_IN AND :today >= :dateFrom))
              AND s.id <> :excludeId
            """)
    long countActiveStaysInBedExcluding(
            @Param("bedId") UUID bedId,
            @Param("agencyId") UUID agencyId,
            @Param("dateFrom") LocalDate dateFrom,
            @Param("effectiveDateTo") LocalDate effectiveDateTo,
            @Param("today") LocalDate today,
            @Param("statuses") List<StayStatus> statuses,
            @Param("excludeId") UUID excludeId
    );

    /**
     * Deliberately cross-tenant: it backs the sweep that promotes PLANNED stays to EXPECTED_TODAY
     * for every agency at once, and runs on a scheduler thread that has no TenantContext.
     *
     * <p>{@code <=}, not {@code =}. Matching the date exactly meant a stay whose arrival date
     * passed while the instance was stopped -- or one planned for today after 06:00 -- was never
     * promoted, and since PLANNED cannot transition to CHECKED_IN it could never be checked in at
     * all. The sweep is idempotent, so catching up costs nothing when there is nothing to catch.
     */
    @Query("SELECT s FROM Stay s WHERE s.status = com.beduno.stay.StayStatus.PLANNED AND s.dateFrom <= :date")
    List<Stay> findPlannedArrivingOnOrBefore(@Param("date") LocalDate date);

    /**
     * Who is in the building on a given date.
     *
     * <p>A CHECKED_IN stay counts regardless of its planned end date. The planned dateTo is an
     * intention, not a fact: extending a contract without updating the stay is routine, and nothing
     * auto-checks-out. Requiring {@code dateTo > date} made an overstaying worker vanish from
     * occupancy and from the inspection roster (where the inspector then reported him as
     * UNEXPECTED_PRESENT) while his bed was offered to somebody else. The override applies only up
     * to {@code today}: who will still be in the building on a future date is a plan, not a fact.
     */
    @Query("""
            SELECT s FROM Stay s
            WHERE s.agencyId = :agencyId
              AND s.propertyId = :propertyId
              AND s.status IN :statuses
              AND s.dateFrom <= :date
              AND (s.dateTo IS NULL OR s.dateTo > :date
                   OR (s.status = com.beduno.stay.StayStatus.CHECKED_IN AND :date <= :today))
            """)
    List<Stay> findActiveStaysForPropertyOnDate(
            @Param("agencyId") UUID agencyId,
            @Param("propertyId") UUID propertyId,
            @Param("date") LocalDate date,
            @Param("today") LocalDate today,
            @Param("statuses") List<StayStatus> statuses
    );

    @Query("""
            SELECT s FROM Stay s
            WHERE s.agencyId = :agencyId
              AND s.propertyId = :propertyId
              AND s.status = com.beduno.stay.StayStatus.EXPECTED_TODAY
              AND s.dateFrom = :date
            """)
    List<Stay> findArrivals(
            @Param("agencyId") UUID agencyId,
            @Param("propertyId") UUID propertyId,
            @Param("date") LocalDate date
    );

    /**
     * Stays that still reserve a bed for this worker, whatever their dates. Backs the delete guard
     * on workers: a soft-deleted worker whose stays stay active holds a bed nobody can use and
     * cannot be checked in, because every stay path that loads the worker now 404s.
     */
    @Query("""
            SELECT COUNT(s) FROM Stay s
            WHERE s.workerId = :workerId
              AND s.agencyId = :agencyId
              AND s.status IN :statuses
            """)
    long countActiveStaysForWorker(
            @Param("workerId") UUID workerId,
            @Param("agencyId") UUID agencyId,
            @Param("statuses") List<StayStatus> statuses
    );

    /** Same half-open overlap semantics as {@link #countActiveStaysInBed}, keyed on the worker. */
    @Query("""
            SELECT COUNT(s) FROM Stay s
            WHERE s.workerId = :workerId
              AND s.agencyId = :agencyId
              AND s.status IN :statuses
              AND s.dateFrom < :effectiveDateTo
              AND (s.dateTo IS NULL OR s.dateTo > :dateFrom
                   OR (s.status = com.beduno.stay.StayStatus.CHECKED_IN AND :today >= :dateFrom))
            """)
    long countOverlappingStaysForWorker(
            @Param("workerId") UUID workerId,
            @Param("agencyId") UUID agencyId,
            @Param("dateFrom") LocalDate dateFrom,
            @Param("effectiveDateTo") LocalDate effectiveDateTo,
            @Param("today") LocalDate today,
            @Param("statuses") List<StayStatus> statuses
    );

    /** As {@link #countOverlappingStaysForWorker}, ignoring the stay being updated. */
    @Query("""
            SELECT COUNT(s) FROM Stay s
            WHERE s.workerId = :workerId
              AND s.agencyId = :agencyId
              AND s.status IN :statuses
              AND s.dateFrom < :effectiveDateTo
              AND (s.dateTo IS NULL OR s.dateTo > :dateFrom
                   OR (s.status = com.beduno.stay.StayStatus.CHECKED_IN AND :today >= :dateFrom))
              AND s.id <> :excludeId
            """)
    long countOverlappingStaysForWorkerExcluding(
            @Param("workerId") UUID workerId,
            @Param("agencyId") UUID agencyId,
            @Param("dateFrom") LocalDate dateFrom,
            @Param("effectiveDateTo") LocalDate effectiveDateTo,
            @Param("today") LocalDate today,
            @Param("statuses") List<StayStatus> statuses,
            @Param("excludeId") UUID excludeId
    );
}
