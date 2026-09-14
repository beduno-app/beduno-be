package com.beduno.stay;

import com.beduno.bed.Bed;
import com.beduno.bed.BedRepository;
import com.beduno.bed.BedStatus;
import com.beduno.common.model.SortFields;
import com.beduno.audit.AuditAction;
import com.beduno.audit.AuditEntityType;
import com.beduno.audit.AuditService;
import com.beduno.common.exception.BusinessException;
import com.beduno.common.exception.ConflictException;
import com.beduno.common.exception.ConstraintViolationException;
import com.beduno.common.exception.ConstraintViolationException.ViolationDetail;
import com.beduno.common.exception.NotFoundException;
import com.beduno.common.exception.ValidationException;
import com.beduno.common.model.PageResponse;
import com.beduno.common.security.SecurityUtils;
import com.beduno.common.security.TenantContext;
import com.beduno.property.Property;
import com.beduno.property.PropertyRepository;
import com.beduno.room.Room;
import com.beduno.room.RoomRepository;
import com.beduno.stay.constraint.ConstraintContext;
import com.beduno.stay.constraint.ConstraintEngine;
import com.beduno.stay.constraint.HardViolation;
import com.beduno.stay.dto.BulkAssignRequest;
import com.beduno.stay.dto.BulkAssignResult;
import com.beduno.stay.dto.BulkAssignResult.AssignmentResult;
import com.beduno.stay.dto.BulkCheckoutRequest;
import com.beduno.stay.dto.BulkCheckoutResult;
import com.beduno.stay.dto.BulkCheckoutResult.CheckoutResult;
import com.beduno.stay.dto.CheckInRequest;
import com.beduno.stay.dto.CheckOutRequest;
import com.beduno.stay.dto.CreateStayRequest;
import com.beduno.stay.dto.MoveRequest;
import com.beduno.stay.dto.NoShowRequest;
import com.beduno.stay.dto.StayResponse;
import com.beduno.stay.dto.UpdateStayRequest;
import com.beduno.worker.Worker;
import com.beduno.worker.WorkerRepository;
import com.beduno.worker.WorkerStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class StayService {

    /**
     * The fields a client may sort stays by, mapped to the columns the native query orders by.
     */
    private static final Map<String, String> SORTABLE = Map.of(
            "dateFrom", "date_from",
            "dateTo", "date_to",
            "status", "status",
            "createdAt", "created_at",
            "updatedAt", "updated_at");

    private final StayRepository stayRepository;
    private final WorkerRepository workerRepository;
    private final RoomRepository roomRepository;
    private final PropertyRepository propertyRepository;
    private final BedRepository bedRepository;
    private final ConstraintEngine constraintEngine;
    private final StayMapper stayMapper;
    private final AuditService auditService;
    private final Clock clock;

    @Transactional(readOnly = true)
    public PageResponse<StayResponse> findAll(
            UUID workerId, UUID propertyId, StayStatus status,
            LocalDate dateFrom, LocalDate dateTo, Pageable pageable) {
        var agencyId = TenantContext.requireAgencyId();
        var page = stayRepository.findAllWithFilters(
                agencyId, workerId, propertyId,
                status != null ? status.name() : null,
                dateFrom, dateTo, SortFields.translate(pageable, SORTABLE));
        return PageResponse.of(page.map(stayMapper::toResponse));
    }

    @Transactional(readOnly = true)
    public StayResponse findById(UUID id) {
        return stayMapper.toResponse(getStayOrThrow(id));
    }

    @Transactional
    public StayResponse create(CreateStayRequest request) {
        var agencyId = TenantContext.requireAgencyId();

        SecurityUtils.requirePropertyAccess(request.propertyId());
        var worker = getWorkerOrThrow(request.workerId(), agencyId);
        var property = getPropertyOrThrow(request.propertyId(), agencyId);
        var room = getRoomInPropertyOrThrow(request.roomId(), request.propertyId(), agencyId);

        var assignment = resolveBed(room, worker, property,
                request.dateFrom(), request.dateTo(), request.bedId(), null, null);
        var ctx = new ConstraintContext(worker, room, property,
                request.dateFrom(), request.dateTo(), null, assignment.bed());
        runConstraints(ctx, request.overrideReason());

        var stay = stayMapper.toEntity(request);
        stay.setAgencyId(agencyId);
        stay.setBedId(assignment.bed().getId());
        stay.setBedAutoAssigned(assignment.autoAssigned());
        stay.setStatus(arrivalStatusFor(request.dateFrom()));
        stay = stayRepository.save(stay);
        auditService.log(agencyId, SecurityUtils.currentUserId(), AuditEntityType.STAY, stay.getId(),
                AuditAction.CREATED, null, snapshot(stay), request.overrideReason());
        return stayMapper.toResponse(stay);
    }

    @Transactional
    public StayResponse update(UUID id, UpdateStayRequest request) {
        var agencyId = TenantContext.requireAgencyId();
        var stay = getStayOrThrow(id);

        if (stay.getStatus() != StayStatus.PLANNED && stay.getStatus() != StayStatus.EXPECTED_TODAY) {
            throw new ConflictException("error.stay.cannot_update_in_current_status");
        }

        var worker = getWorkerOrThrow(stay.getWorkerId(), agencyId);
        var property = getPropertyOrThrow(stay.getPropertyId(), agencyId);
        var room = getRoomInPropertyOrThrow(request.roomId(), stay.getPropertyId(), agencyId);

        var assignment = resolveBedKeepingCurrent(stay, room, worker, property,
                request.dateFrom(), request.dateTo(), request.bedId(), request.roomId());
        var ctx = new ConstraintContext(worker, room, property,
                request.dateFrom(), request.dateTo(), stay.getId(), assignment.bed());
        runConstraints(ctx, request.overrideReason());

        var previous = snapshot(stay);
        stayMapper.updateEntity(request, stay);
        stay.setBedId(assignment.bed().getId());
        stay.setBedAutoAssigned(assignment.autoAssigned());
        // Reconcile the status with the new arrival date, in both directions. EXPECTED_TODAY only
        // means anything while dateFrom is today or past: an arrival postponed by a week used to
        // stay EXPECTED_TODAY, so it could still be checked in today and showed up as a phantom
        // arrival for the whole intervening week.
        stay.setStatus(arrivalStatusFor(stay.getDateFrom()));
        stay = stayRepository.save(stay);
        auditService.log(agencyId, SecurityUtils.currentUserId(), AuditEntityType.STAY, stay.getId(),
                AuditAction.UPDATED, previous, snapshot(stay), request.overrideReason());
        return stayMapper.toResponse(stay);
    }

    @Transactional(readOnly = true)
    public List<StayResponse> getArrivals(UUID propertyId, LocalDate date) {
        var agencyId = TenantContext.requireAgencyId();
        SecurityUtils.requirePropertyAccess(propertyId);
        return stayRepository.findArrivals(agencyId, propertyId, date)
                .stream().map(stayMapper::toResponse).toList();
    }

    @Transactional
    public StayResponse checkIn(UUID id, CheckInRequest request) {
        var agencyId = TenantContext.requireAgencyId();
        var stay = getStayOrThrow(id);

        if (!stay.getStatus().canTransitionTo(StayStatus.CHECKED_IN)) {
            throw new ConflictException("error.stay.invalid_status_transition");
        }

        var targetRoomId = request.roomId() != null ? request.roomId() : stay.getRoomId();
        var room = getRoomInPropertyOrThrow(targetRoomId, stay.getPropertyId(), agencyId);
        var worker = getWorkerOrThrow(stay.getWorkerId(), agencyId);
        var property = getPropertyOrThrow(stay.getPropertyId(), agencyId);

        var assignment = resolveBedKeepingCurrent(stay, room, worker, property,
                stay.getDateFrom(), stay.getDateTo(), request.bedId(), request.roomId());
        var ctx = new ConstraintContext(worker, room, property,
                stay.getDateFrom(), stay.getDateTo(), stay.getId(), assignment.bed());
        runConstraints(ctx, request.overrideReason());

        // Snapshot before any mutation. Taken after the room and bed were already written, the
        // audit event showed previousState.roomId == newState.roomId and the planned room became
        // unrecoverable -- exactly what an auditor is pointed at the trail to find out.
        var previous = snapshot(stay);

        if (request.roomId() != null) {
            stay.setRoomId(request.roomId());
        }
        stay.setBedId(assignment.bed().getId());
        stay.setBedAutoAssigned(assignment.autoAssigned());
        stay.setStatus(StayStatus.CHECKED_IN);
        stay.setConfirmedByUserId(SecurityUtils.currentUserId());
        stay = stayRepository.save(stay);
        auditService.log(stay.getAgencyId(), SecurityUtils.currentUserId(), AuditEntityType.STAY, stay.getId(),
                AuditAction.CHECKED_IN, previous, snapshot(stay), request.overrideReason());
        return stayMapper.toResponse(stay);
    }

    @Transactional
    public StayResponse noShow(UUID id, NoShowRequest request) {
        var stay = getStayOrThrow(id);
        if (!stay.getStatus().canTransitionTo(StayStatus.NO_SHOW)) {
            throw new ConflictException("error.stay.invalid_status_transition");
        }
        var previous = snapshot(stay);
        stay.setStatus(StayStatus.NO_SHOW);
        stay.setNoShowReason(request.noShowReason());
        stay = stayRepository.save(stay);
        auditService.log(stay.getAgencyId(), SecurityUtils.currentUserId(), AuditEntityType.STAY, stay.getId(),
                AuditAction.NO_SHOW, previous, snapshot(stay), request.noShowReason());
        return stayMapper.toResponse(stay);
    }

    @Transactional
    public StayResponse move(UUID id, MoveRequest request) {
        var agencyId = TenantContext.requireAgencyId();
        var stay = getStayOrThrow(id);

        if (stay.getStatus() != StayStatus.CHECKED_IN) {
            throw new ConflictException("error.stay.cannot_move_in_current_status");
        }

        var targetRoom = getRoomInPropertyOrThrow(request.targetRoomId(), stay.getPropertyId(), agencyId);
        var worker = getWorkerOrThrow(stay.getWorkerId(), agencyId);
        var property = getPropertyOrThrow(stay.getPropertyId(), agencyId);
        var today = LocalDate.now(clock);

        var originalDateTo = stay.getDateTo();

        // The replacement stay runs [today, originalDateTo), which the chk_stays_dates
        // CHECK rejects unless date_to is strictly after date_from. On the final day
        // there is no night left to reassign, so refuse instead of failing at the DB.
        if (originalDateTo != null && !originalDateTo.isAfter(today)) {
            throw new ConflictException("error.stay.cannot_move_on_last_day");
        }

        // Auto-assign must not hand back the bed the worker is already in. resolveBed excludes
        // this stay from the occupancy counts, so the current bed looks free and -- sorted by
        // label -- is usually the first candidate; a same-room move with no explicit target was
        // therefore refused as "same bed" even with the rest of the room empty.
        var assignment = resolveBed(targetRoom, worker, property,
                today, originalDateTo, request.targetBedId(), stay.getId(), stay.getBedId());
        if (stay.getBedId().equals(assignment.bed().getId())) {
            throw new ConflictException("error.stay.move_same_room");
        }

        var ctx = new ConstraintContext(worker, targetRoom, property,
                today, originalDateTo, stay.getId(), assignment.bed());
        runConstraints(ctx, request.overrideReason());

        var previousStay = snapshot(stay);
        stay.setStatus(StayStatus.CHECKED_OUT);
        // Flushed before the replacement is inserted. A move ends one stay and starts another for
        // the same worker over the remainder of the same period; while the check-out sits
        // unflushed in the session, both rows are active at once and the database's overlap
        // constraints reject the pair.
        stayRepository.saveAndFlush(stay);
        auditService.log(agencyId, SecurityUtils.currentUserId(), AuditEntityType.STAY, stay.getId(),
                AuditAction.CHECKED_OUT, previousStay, snapshot(stay), null);

        var newStay = new Stay();
        newStay.setAgencyId(agencyId);
        newStay.setWorkerId(stay.getWorkerId());
        newStay.setPropertyId(stay.getPropertyId());
        newStay.setRoomId(request.targetRoomId());
        newStay.setBedId(assignment.bed().getId());
        newStay.setBedAutoAssigned(assignment.autoAssigned());
        newStay.setDateFrom(today);
        newStay.setDateTo(originalDateTo);
        newStay.setStatus(StayStatus.CHECKED_IN);
        newStay.setConfirmedByUserId(SecurityUtils.currentUserId());
        newStay = stayRepository.save(newStay);
        auditService.log(agencyId, SecurityUtils.currentUserId(), AuditEntityType.STAY, newStay.getId(),
                AuditAction.MOVED, null, snapshot(newStay), request.overrideReason());
        return stayMapper.toResponse(newStay);
    }

    @Transactional
    public StayResponse checkOut(UUID id, CheckOutRequest request) {
        var stay = getStayOrThrow(id);
        if (!stay.getStatus().canTransitionTo(StayStatus.CHECKED_OUT)) {
            throw new ConflictException("error.stay.invalid_status_transition");
        }
        var previous = snapshot(stay);
        if (request.actualDateTo() != null) {
            // A worker who leaves the day he arrived is a real case, but the half-open period
            // cannot express it: chk_stays_dates requires date_to > date_from, and reaching it
            // meant a 500 and no check-out at all.
            if (!request.actualDateTo().isAfter(stay.getDateFrom())) {
                throw new ValidationException("error.stay.invalid_dates");
            }
            stay.setDateTo(request.actualDateTo());
        }
        stay.setStatus(StayStatus.CHECKED_OUT);
        stay = stayRepository.save(stay);
        auditService.log(stay.getAgencyId(), SecurityUtils.currentUserId(), AuditEntityType.STAY, stay.getId(),
                AuditAction.CHECKED_OUT, previous, snapshot(stay), null);
        return stayMapper.toResponse(stay);
    }

    /**
     * Promotes every due PLANNED stay across all agencies. Runs without a TenantContext, from the
     * scheduler and once at start-up, so it passes each stay's own agencyId to the audit log and
     * records no actor -- the change has no human author.
     */
    @Transactional
    public int transitionPlannedToExpectedToday(LocalDate date) {
        var stays = stayRepository.findPlannedArrivingOnOrBefore(date);
        for (var stay : stays) {
            var previous = snapshot(stay);
            stay.setStatus(StayStatus.EXPECTED_TODAY);
            // Every other status change on a stay is audited and the trail is documented as
            // covering all of them. Without this the next event on the stay showed a previous
            // status of EXPECTED_TODAY with nothing recording when it left PLANNED.
            auditService.log(stay.getAgencyId(), null, AuditEntityType.STAY, stay.getId(),
                    AuditAction.UPDATED, previous, snapshot(stay), "scheduler");
        }
        stayRepository.saveAll(stays);
        return stays.size();
    }

    @Transactional
    public void cancel(UUID id) {
        var stay = getStayOrThrow(id);
        if (!stay.getStatus().canTransitionTo(StayStatus.CANCELLED)) {
            throw new ConflictException("error.stay.invalid_status_transition");
        }
        var previous = snapshot(stay);
        stay.setStatus(StayStatus.CANCELLED);
        stayRepository.save(stay);
        auditService.log(stay.getAgencyId(), SecurityUtils.currentUserId(), AuditEntityType.STAY, stay.getId(),
                AuditAction.CANCELLED, previous, snapshot(stay), null);
    }

    /**
     * Per-item results, one transaction.
     *
     * <p>Only business failures are reported per item, and only business failures leave the other
     * items intact. That is a deliberate contract, not an omission. Anything else -- a database
     * rejecting a write -- puts the Hibernate session in a state where no later item can be
     * persisted either, so it is rethrown and the whole batch rolls back with the proper error
     * envelope for the cause.
     *
     * <p>What this replaces: every failure was swallowed into a per-item "error" entry, but the
     * transaction had already been marked rollback-only, so the caller received a 500 and a result
     * list describing creations that never happened. It also blamed the wrong item. Entities use
     * GenerationType.UUID, so save() alone does not reach the database; the INSERT ran on the next
     * item's auto-flush, and that item was recorded as the failure. saveAndFlush pins each INSERT
     * inside the try block that owns it.
     */
    @Transactional
    public BulkAssignResult bulkAssign(BulkAssignRequest request) {
        var agencyId = TenantContext.requireAgencyId();
        var actorId = SecurityUtils.currentUserId();
        var results = new ArrayList<AssignmentResult>();
        int created = 0;
        int errors = 0;

        for (int i = 0; i < request.assignments().size(); i++) {
            var a = request.assignments().get(i);
            try {
                SecurityUtils.requirePropertyAccess(a.propertyId());
                var worker = getWorkerOrThrow(a.workerId(), agencyId);
                var property = getPropertyOrThrow(a.propertyId(), agencyId);
                var room = getRoomInPropertyOrThrow(a.roomId(), a.propertyId(), agencyId);
                var assignment = resolveBed(room, worker, property, a.dateFrom(), a.dateTo(), a.bedId(), null, null);
                var ctx = new ConstraintContext(worker, room, property, a.dateFrom(), a.dateTo(), null, assignment.bed());
                runConstraints(ctx, a.overrideReason());

                var stay = new Stay();
                stay.setAgencyId(agencyId);
                stay.setWorkerId(a.workerId());
                stay.setPropertyId(a.propertyId());
                stay.setRoomId(a.roomId());
                stay.setBedId(assignment.bed().getId());
                stay.setBedAutoAssigned(assignment.autoAssigned());
                stay.setDateFrom(a.dateFrom());
                stay.setDateTo(a.dateTo());
                stay.setOverrideReason(a.overrideReason());
                stay.setStatus(arrivalStatusFor(a.dateFrom()));
                stay = stayRepository.saveAndFlush(stay);
                auditService.log(agencyId, actorId, AuditEntityType.STAY, stay.getId(),
                        AuditAction.BULK_ASSIGNED, null, snapshot(stay), a.overrideReason());
                results.add(new AssignmentResult(i, a.workerId(), stay.getId(), stay.getBedId(), "created", null));
                created++;
            } catch (BusinessException e) {
                // A message code, never e.getMessage(): that leaked raw SQL text for non-business
                // failures and was null for an NPE.
                log.warn("Bulk assign item {} rejected: {}", i, e.getMessageCode());
                results.add(new AssignmentResult(i, a.workerId(), null, null, "error", e.getMessageCode()));
                errors++;
            }
        }
        return new BulkAssignResult(created, errors, List.copyOf(results));
    }

    @Transactional
    public BulkCheckoutResult bulkCheckout(BulkCheckoutRequest request) {
        var agencyId = TenantContext.requireAgencyId();
        var actorId = SecurityUtils.currentUserId();
        var results = new ArrayList<CheckoutResult>();
        int checkedOut = 0;
        int errors = 0;

        for (var stayId : request.stayIds()) {
            try {
                var stay = stayRepository.findByIdAndAgencyId(stayId, agencyId)
                        .orElseThrow(() -> new NotFoundException("error.stay.not_found"));
                if (!stay.getStatus().canTransitionTo(StayStatus.CHECKED_OUT)) {
                    results.add(new CheckoutResult(stayId, "error", "error.stay.invalid_status_transition"));
                    errors++;
                    continue;
                }
                var previous = snapshot(stay);
                stay.setStatus(StayStatus.CHECKED_OUT);
                stayRepository.saveAndFlush(stay);
                auditService.log(agencyId, actorId, AuditEntityType.STAY, stayId,
                        AuditAction.BULK_CHECKED_OUT, previous, snapshot(stay), null);
                results.add(new CheckoutResult(stayId, "checked_out", null));
                checkedOut++;
            } catch (BusinessException e) {
                // See bulkAssign: business failures only, message codes only.
                log.warn("Bulk checkout of {} rejected: {}", stayId, e.getMessageCode());
                results.add(new CheckoutResult(stayId, "error", e.getMessageCode()));
                errors++;
            }
        }
        return new BulkCheckoutResult(checkedOut, errors, List.copyOf(results));
    }

    private void runConstraints(ConstraintContext ctx, String overrideReason) {
        var result = constraintEngine.evaluate(ctx);

        if (!result.isAllowed()) {
            throw new ConstraintViolationException(
                    "error.constraint.violated",
                    toViolationDetails(result.hardViolations())
            );
        }

        // Blank counts as absent. An empty string suppressed the warning just as well as a real
        // justification did, and was then persisted and audited as the reason it was overridden.
        if (result.hasWarnings() && (overrideReason == null || overrideReason.isBlank())) {
            throw new ConstraintViolationException(
                    "error.constraint.soft_violations",
                    toViolationDetails(result.softViolations())
            );
        }
    }

    private List<ViolationDetail> toViolationDetails(List<? extends com.beduno.stay.constraint.Violation> violations) {
        return violations.stream()
                .map(v -> new ViolationDetail(v.type(), null, v.message(), v.params()))
                .toList();
    }


    private record BedAssignment(Bed bed, boolean autoAssigned) {}

    /**
     * Resolves which bed a worker occupies -- the planner's explicit choice, or the system's
     * pick -- used by every write path instead of duplicating auto-assign logic five times.
     *
     * <p>This only decides *which* bed; it never throws for an explicit choice that turns out to
     * violate a constraint (e.g. an occupied or blocked bed) -- that enforcement, including the
     * override-reason-vs-soft-violation semantics, is left entirely to the caller's own
     * ConstraintContext/runConstraints call immediately afterward, exactly as it already handles
     * room/worker/property checks. Auto-assign is the one case that must evaluate the engine here,
     * since it needs to know whether a candidate is viable before committing to it.
     */
    private BedAssignment resolveBed(Room room, Worker worker, Property property,
                                      LocalDate dateFrom, LocalDate dateTo,
                                      UUID requestedBedId, UUID excludeStayId, UUID excludeBedId) {
        if (requestedBedId != null) {
            var bed = bedRepository.findByIdAndAgencyIdAndRoomId(requestedBedId, room.getAgencyId(), room.getId())
                    .orElseThrow(() -> new ValidationException("error.bed.not_in_room"));
            return new BedAssignment(bed, false);
        }

        var candidates = bedRepository.findAllByAgencyIdAndRoomId(room.getAgencyId(), room.getId()).stream()
                .filter(bed -> bed.getStatus() == BedStatus.ACTIVE)
                .filter(bed -> excludeBedId == null || !bed.getId().equals(excludeBedId))
                .sorted(Comparator.comparing(Bed::getLabel))
                .toList();

        if (candidates.isEmpty()) {
            throw new ConstraintViolationException("error.constraint.violated", List.of(new ViolationDetail(
                    "BED_UNAVAILABLE", null, "constraint.bed.unavailable",
                    Map.of("roomNumber", room.getRoomNumber()))));
        }

        List<HardViolation> firstCandidateViolations = null;
        for (var bed : candidates) {
            var ctx = new ConstraintContext(worker, room, property, dateFrom, dateTo, excludeStayId, bed);
            var result = constraintEngine.evaluate(ctx);
            if (result.isAllowed()) {
                return new BedAssignment(bed, true);
            }
            if (firstCandidateViolations == null) {
                firstCandidateViolations = result.hardViolations();
            }
        }
        throw new ConstraintViolationException("error.constraint.violated", toViolationDetails(firstCandidateViolations));
    }

    /**
     * Bed for a write path that is not necessarily moving the worker. An explicit bedId always
     * wins. Otherwise, when the room is unchanged, the stay keeps the bed it already has --
     * including its bedAutoAssigned flag.
     *
     * <p>Re-running the auto-assign loop here was wrong: it excludes this stay from the occupancy
     * counts, so it returns the lowest-labelled free bed in the room, not the bed the planner
     * chose. A front-desk check-in with the documented minimal body {@code {}} silently moved the
     * worker to a different bed than the printed arrivals sheet said, and a notes-only PUT did
     * the same.
     */
    private BedAssignment resolveBedKeepingCurrent(Stay stay, Room room, Worker worker, Property property,
                                                    LocalDate dateFrom, LocalDate dateTo,
                                                    UUID requestedBedId, UUID requestedRoomId) {
        var roomUnchanged = requestedRoomId == null || requestedRoomId.equals(stay.getRoomId());
        if (requestedBedId == null && roomUnchanged && stay.getBedId() != null) {
            var bed = bedRepository
                    .findByIdAndAgencyIdAndRoomId(stay.getBedId(), room.getAgencyId(), room.getId())
                    .orElseThrow(() -> new ValidationException("error.bed.not_in_room"));
            return new BedAssignment(bed, stay.isBedAutoAssigned());
        }
        return resolveBed(room, worker, property, dateFrom, dateTo, requestedBedId, stay.getId(), null);
    }

    /**
     * The status a stay should carry given its arrival date.
     *
     * <p>PLANNED has no transition to CHECKED_IN, and for a long time the only way out of it was a
     * once-a-day sweep keyed on {@code dateFrom = today}. A stay created at 10:00 for a worker
     * arriving tonight was therefore never checkable in -- {@code POST /check-in} returned 409
     * forever and a direct database update was the only workaround. Deciding the status here, and
     * again on every update, closes that without loosening the transition table.
     */
    private StayStatus arrivalStatusFor(LocalDate dateFrom) {
        return dateFrom.isAfter(LocalDate.now(clock)) ? StayStatus.PLANNED : StayStatus.EXPECTED_TODAY;
    }


    /**
     * Every single-stay operation -- read, update, cancel, check-in, check-out, no-show, move --
     * resolves the stay through here, which is why the property-scope check belongs here rather
     * than repeated at seven call sites. A PROPERTY_ADMIN or FRONT_DESK holding a token for
     * property A could previously operate on any stay in the agency, including one at property B.
     */
    private Stay getStayOrThrow(UUID id) {
        var agencyId = TenantContext.requireAgencyId();
        var stay = stayRepository.findByIdAndAgencyId(id, agencyId)
                .orElseThrow(() -> new NotFoundException("error.stay.not_found"));
        SecurityUtils.requirePropertyAccess(stay.getPropertyId());
        return stay;
    }

    private Worker getWorkerOrThrow(UUID workerId, UUID agencyId) {
        return workerRepository.findByIdAndAgencyIdAndStatusNot(workerId, agencyId, WorkerStatus.DELETED)
                .orElseThrow(() -> new NotFoundException("error.worker.not_found"));
    }

    private Room getRoomOrThrow(UUID roomId, UUID agencyId) {
        return roomRepository.findByIdAndAgencyId(roomId, agencyId)
                .orElseThrow(() -> new NotFoundException("error.room.not_found"));
    }

    /**
     * Room lookup for write paths, which must also prove the room belongs to the stay's property.
     * A stay whose room sits in a different property than its propertyId is invisible everywhere:
     * the occupancy, inspection and exception views for the stay's property fetch it and then drop
     * it because they have no matching room, and the views for the room's property never fetch it
     * at all. The worker is checked in and appears nowhere.
     */
    private Room getRoomInPropertyOrThrow(UUID roomId, UUID propertyId, UUID agencyId) {
        return roomRepository.findByIdAndAgencyIdAndPropertyId(roomId, agencyId, propertyId)
                .orElseThrow(() -> new NotFoundException("error.room.not_found"));
    }

    private Property getPropertyOrThrow(UUID propertyId, UUID agencyId) {
        return propertyRepository.findByIdAndAgencyId(propertyId, agencyId)
                .orElseThrow(() -> new NotFoundException("error.property.not_found"));
    }

    private Map<String, Object> snapshot(Stay stay) {
        var map = new LinkedHashMap<String, Object>();
        map.put("status", stay.getStatus().name());
        map.put("workerId", stay.getWorkerId().toString());
        map.put("roomId", stay.getRoomId().toString());
        map.put("bedId", stay.getBedId().toString());
        map.put("bedAutoAssigned", stay.isBedAutoAssigned());
        map.put("propertyId", stay.getPropertyId().toString());
        map.put("dateFrom", stay.getDateFrom().toString());
        if (stay.getDateTo() != null) {
            map.put("dateTo", stay.getDateTo().toString());
        }
        if (stay.getNoShowReason() != null) {
            map.put("noShowReason", stay.getNoShowReason());
        }
        return map;
    }
}
