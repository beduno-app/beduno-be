package com.bedok.stay;

import com.bedok.common.exception.ConflictException;
import com.bedok.common.exception.ConstraintViolationException;
import com.bedok.common.exception.ConstraintViolationException.ViolationDetail;
import com.bedok.common.exception.NotFoundException;
import com.bedok.common.model.PageResponse;
import com.bedok.common.security.TenantContext;
import com.bedok.property.PropertyRepository;
import com.bedok.room.Room;
import com.bedok.room.RoomRepository;
import com.bedok.stay.constraint.ConstraintContext;
import com.bedok.stay.constraint.ConstraintEngine;
import com.bedok.stay.dto.CreateStayRequest;
import com.bedok.stay.dto.StayResponse;
import com.bedok.stay.dto.UpdateStayRequest;
import com.bedok.worker.Worker;
import com.bedok.worker.WorkerRepository;
import com.bedok.worker.WorkerStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
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

        stayMapper.updateEntity(request, stay);
        stay = stayRepository.save(stay);
        return stayMapper.toResponse(stay);
    }

    @Transactional
    public void cancel(UUID id) {
        var stay = getStayOrThrow(id);
        if (!stay.getStatus().canTransitionTo(StayStatus.CANCELLED)) {
            throw new ConflictException("error.stay.invalid_status_transition");
        }
        stay.setStatus(StayStatus.CANCELLED);
        stayRepository.save(stay);
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

    private List<ViolationDetail> toViolationDetails(List<? extends com.bedok.stay.constraint.Violation> violations) {
        return violations.stream()
                .map(v -> new ViolationDetail(v.type(), null, v.message(), toStringMap(v.params())))
                .toList();
    }

    private Map<String, Object> toStringMap(Map<String, Object> params) {
        return params;
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

    private com.bedok.property.Property getPropertyOrThrow(UUID propertyId, UUID agencyId) {
        return propertyRepository.findByIdAndAgencyId(propertyId, agencyId)
                .orElseThrow(() -> new NotFoundException("error.property.not_found"));
    }
}
