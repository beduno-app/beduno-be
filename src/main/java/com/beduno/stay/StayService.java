package com.beduno.stay;

import com.beduno.audit.AuditAction;
import com.beduno.audit.AuditEntityType;
import com.beduno.audit.AuditService;
import com.beduno.common.exception.ConflictException;
import com.beduno.common.exception.ConstraintViolationException;
import com.beduno.common.exception.ConstraintViolationException.ViolationDetail;
import com.beduno.common.exception.NotFoundException;
import com.beduno.common.model.PageResponse;
import com.beduno.common.security.TenantContext;
import com.beduno.property.PropertyRepository;
import com.beduno.room.Room;
import com.beduno.room.RoomRepository;
import com.beduno.stay.constraint.ConstraintContext;
import com.beduno.stay.constraint.ConstraintEngine;
import com.beduno.common.security.CurrentUser;
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
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class StayService {

    private final StayRepository stayRepository;
    private final WorkerRepository workerRepository;
    private final RoomRepository roomRepository;
    private final PropertyRepository propertyRepository;
    private final ConstraintEngine constraintEngine;
    private final StayMapper stayMapper;
    private final AuditService auditService;

    @Transactional(readOnly = true)
    public PageResponse<StayResponse> findAll(
            UUID workerId, UUID propertyId, StayStatus status,
            LocalDate dateFrom, LocalDate dateTo, Pageable pageable) {
        var agencyId = TenantContext.requireAgencyId();
        var page = stayRepository.findAllWithFilters(
                agencyId, workerId, propertyId,
                status != null ? status.name() : null,
                dateFrom, dateTo, pageable);
        return PageResponse.of(page.map(stayMapper::toResponse));
    }

    @Transactional(readOnly = true)
    public StayResponse findById(UUID id) {
        return stayMapper.toResponse(getStayOrThrow(id));
    }

    @Transactional
    public StayResponse create(CreateStayRequest request) {
        var agencyId = TenantContext.requireAgencyId();

        var worker = getWorkerOrThrow(request.workerId(), agencyId);
        var room = getRoomOrThrow(request.roomId(), agencyId);
        var property = getPropertyOrThrow(request.propertyId(), agencyId);

        var ctx = new ConstraintContext(worker, room, property,
                request.dateFrom(), request.dateTo(), null);
        runConstraints(ctx, request.overrideReason());

        var stay = stayMapper.toEntity(request);
        stay.setAgencyId(agencyId);
        stay.setStatus(StayStatus.PLANNED);
        stay = stayRepository.save(stay);
        auditService.log(agencyId, currentUserId(), AuditEntityType.STAY, stay.getId(),
                AuditAction.CREATED, null, snapshot(stay), null);
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
        var room = getRoomOrThrow(request.roomId(), agencyId);
        var property = getPropertyOrThrow(stay.getPropertyId(), agencyId);

        var ctx = new ConstraintContext(worker, room, property,
                request.dateFrom(), request.dateTo(), stay.getId());
        runConstraints(ctx, request.overrideReason());

        var previous = snapshot(stay);
        stayMapper.updateEntity(request, stay);
        stay = stayRepository.save(stay);
        auditService.log(agencyId, currentUserId(), AuditEntityType.STAY, stay.getId(),
                AuditAction.UPDATED, previous, snapshot(stay), request.overrideReason());
        return stayMapper.toResponse(stay);
    }

    @Transactional(readOnly = true)
    public List<StayResponse> getArrivals(UUID propertyId, LocalDate date) {
        var agencyId = TenantContext.requireAgencyId();
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
        var room = getRoomOrThrow(targetRoomId, agencyId);
        var worker = getWorkerOrThrow(stay.getWorkerId(), agencyId);
        var property = getPropertyOrThrow(stay.getPropertyId(), agencyId);

        var ctx = new ConstraintContext(worker, room, property,
                stay.getDateFrom(), stay.getDateTo(), stay.getId());
        runConstraints(ctx, request.overrideReason());

        if (request.roomId() != null) {
            stay.setRoomId(request.roomId());
        }
        var previous = snapshot(stay);
        stay.setStatus(StayStatus.CHECKED_IN);
        stay.setConfirmedByUserId(currentUserId());
        stay = stayRepository.save(stay);
        auditService.log(stay.getAgencyId(), currentUserId(), AuditEntityType.STAY, stay.getId(),
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
        stay.setNotes(request.reasonTag());
        stay = stayRepository.save(stay);
        auditService.log(stay.getAgencyId(), currentUserId(), AuditEntityType.STAY, stay.getId(),
                AuditAction.NO_SHOW, previous, snapshot(stay), request.reasonTag());
        return stayMapper.toResponse(stay);
    }

    @Transactional
    public StayResponse move(UUID id, MoveRequest request) {
        var agencyId = TenantContext.requireAgencyId();
        var stay = getStayOrThrow(id);

        if (stay.getStatus() != StayStatus.CHECKED_IN) {
            throw new ConflictException("error.stay.cannot_move_in_current_status");
        }
        if (stay.getRoomId().equals(request.targetRoomId())) {
            throw new ConflictException("error.stay.move_same_room");
        }

        var targetRoom = getRoomOrThrow(request.targetRoomId(), agencyId);
        var worker = getWorkerOrThrow(stay.getWorkerId(), agencyId);
        var property = getPropertyOrThrow(stay.getPropertyId(), agencyId);
        var today = LocalDate.now();

        var originalDateTo = stay.getDateTo();
        var ctx = new ConstraintContext(worker, targetRoom, property,
                today, originalDateTo, stay.getId());
        runConstraints(ctx, request.overrideReason());

        var previousStay = snapshot(stay);
        stay.setStatus(StayStatus.CHECKED_OUT);
        stayRepository.save(stay);
        auditService.log(agencyId, currentUserId(), AuditEntityType.STAY, stay.getId(),
                AuditAction.CHECKED_OUT, previousStay, snapshot(stay), null);

        var newStay = new Stay();
        newStay.setAgencyId(agencyId);
        newStay.setWorkerId(stay.getWorkerId());
        newStay.setPropertyId(stay.getPropertyId());
        newStay.setRoomId(request.targetRoomId());
        newStay.setDateFrom(today);
        newStay.setDateTo(originalDateTo);
        newStay.setStatus(StayStatus.CHECKED_IN);
        newStay.setConfirmedByUserId(currentUserId());
        newStay = stayRepository.save(newStay);
        auditService.log(agencyId, currentUserId(), AuditEntityType.STAY, newStay.getId(),
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
            stay.setDateTo(request.actualDateTo());
        }
        stay.setStatus(StayStatus.CHECKED_OUT);
        stay = stayRepository.save(stay);
        auditService.log(stay.getAgencyId(), currentUserId(), AuditEntityType.STAY, stay.getId(),
                AuditAction.CHECKED_OUT, previous, snapshot(stay), null);
        return stayMapper.toResponse(stay);
    }

    @Transactional
    public int transitionPlannedToExpectedToday(LocalDate date) {
        var stays = stayRepository.findPlannedArrivingOn(date);
        stays.forEach(s -> s.setStatus(StayStatus.EXPECTED_TODAY));
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
        auditService.log(stay.getAgencyId(), currentUserId(), AuditEntityType.STAY, stay.getId(),
                AuditAction.CANCELLED, previous, snapshot(stay), null);
    }

    @Transactional
    public BulkAssignResult bulkAssign(BulkAssignRequest request) {
        var agencyId = TenantContext.requireAgencyId();
        var actorId = currentUserId();
        var results = new ArrayList<AssignmentResult>();
        int created = 0;
        int errors = 0;

        for (int i = 0; i < request.assignments().size(); i++) {
            var a = request.assignments().get(i);
            try {
                var worker = getWorkerOrThrow(a.workerId(), agencyId);
                var room = getRoomOrThrow(a.roomId(), agencyId);
                var property = getPropertyOrThrow(a.propertyId(), agencyId);
                var ctx = new ConstraintContext(worker, room, property, a.dateFrom(), a.dateTo(), null);
                runConstraints(ctx, a.overrideReason());

                var stay = new Stay();
                stay.setAgencyId(agencyId);
                stay.setWorkerId(a.workerId());
                stay.setPropertyId(a.propertyId());
                stay.setRoomId(a.roomId());
                stay.setDateFrom(a.dateFrom());
                stay.setDateTo(a.dateTo());
                stay.setOverrideReason(a.overrideReason());
                stay.setStatus(StayStatus.PLANNED);
                stay = stayRepository.save(stay);
                auditService.log(agencyId, actorId, AuditEntityType.STAY, stay.getId(),
                        AuditAction.BULK_ASSIGNED, null, snapshot(stay), null);
                results.add(new AssignmentResult(i, a.workerId(), stay.getId(), "created", null));
                created++;
            } catch (Exception e) {
                results.add(new AssignmentResult(i, a.workerId(), null, "error", e.getMessage()));
                errors++;
            }
        }
        return new BulkAssignResult(created, errors, List.copyOf(results));
    }

    @Transactional
    public BulkCheckoutResult bulkCheckout(BulkCheckoutRequest request) {
        var agencyId = TenantContext.requireAgencyId();
        var actorId = currentUserId();
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
                stayRepository.save(stay);
                auditService.log(agencyId, actorId, AuditEntityType.STAY, stayId,
                        AuditAction.BULK_CHECKED_OUT, previous, snapshot(stay), null);
                results.add(new CheckoutResult(stayId, "checked_out", null));
                checkedOut++;
            } catch (Exception e) {
                results.add(new CheckoutResult(stayId, "error", e.getMessage()));
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

        if (result.hasWarnings() && overrideReason == null) {
            throw new ConstraintViolationException(
                    "error.constraint.soft_violations",
                    toViolationDetails(result.softViolations())
            );
        }
    }

    private List<ViolationDetail> toViolationDetails(List<? extends com.beduno.stay.constraint.Violation> violations) {
        return violations.stream()
                .map(v -> new ViolationDetail(v.type(), null, v.message(), toStringMap(v.params())))
                .toList();
    }

    private Map<String, Object> toStringMap(Map<String, Object> params) {
        return params;
    }

    private UUID currentUserId() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof CurrentUser currentUser) {
            return currentUser.userId();
        }
        return null;
    }

    private Stay getStayOrThrow(UUID id) {
        var agencyId = TenantContext.requireAgencyId();
        return stayRepository.findByIdAndAgencyId(id, agencyId)
                .orElseThrow(() -> new NotFoundException("error.stay.not_found"));
    }

    private Worker getWorkerOrThrow(UUID workerId, UUID agencyId) {
        return workerRepository.findByIdAndAgencyIdAndStatusNot(workerId, agencyId, WorkerStatus.DELETED)
                .orElseThrow(() -> new NotFoundException("error.worker.not_found"));
    }

    private Room getRoomOrThrow(UUID roomId, UUID agencyId) {
        return roomRepository.findByIdAndAgencyId(roomId, agencyId)
                .orElseThrow(() -> new NotFoundException("error.room.not_found"));
    }

    private com.beduno.property.Property getPropertyOrThrow(UUID propertyId, UUID agencyId) {
        return propertyRepository.findByIdAndAgencyId(propertyId, agencyId)
                .orElseThrow(() -> new NotFoundException("error.property.not_found"));
    }

    private Map<String, Object> snapshot(Stay stay) {
        var map = new LinkedHashMap<String, Object>();
        map.put("status", stay.getStatus().name());
        map.put("workerId", stay.getWorkerId().toString());
        map.put("roomId", stay.getRoomId().toString());
        map.put("propertyId", stay.getPropertyId().toString());
        map.put("dateFrom", stay.getDateFrom().toString());
        if (stay.getDateTo() != null) {
            map.put("dateTo", stay.getDateTo().toString());
        }
        return map;
    }
}
