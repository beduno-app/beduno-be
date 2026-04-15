package com.bedok.worker;

import com.bedok.audit.AuditAction;
import com.bedok.audit.AuditEntityType;
import com.bedok.audit.AuditService;
import com.bedok.common.exception.ConflictException;
import com.bedok.common.exception.NotFoundException;
import com.bedok.common.model.PageResponse;
import com.bedok.common.security.CurrentUser;
import com.bedok.common.security.TenantContext;
import com.bedok.worker.dto.CreateWorkerRequest;
import com.bedok.worker.dto.UpdateWorkerRequest;
import com.bedok.worker.dto.WorkerResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class WorkerService {

    private final WorkerRepository workerRepository;
    private final WorkerMapper workerMapper;
    private final AuditService auditService;

    @Transactional(readOnly = true)
    public PageResponse<WorkerResponse> findAll(WorkerStatus status, Gender gender, String tag, String search, Pageable pageable) {
        var agencyId = TenantContext.requireAgencyId();

        var page = (tag != null)
                ? workerRepository.findAllByAgencyIdWithFiltersAndTag(
                        agencyId,
                        status != null ? status.name() : null,
                        gender != null ? gender.name() : null,
                        search, tag, pageable)
                : workerRepository.findAllByAgencyIdWithFilters(
                        agencyId,
                        status != null ? status.name() : null,
                        gender != null ? gender.name() : null,
                        search, pageable);

        return PageResponse.of(page.map(workerMapper::toResponse));
    }

    @Transactional(readOnly = true)
    public WorkerResponse findById(UUID id) {
        var worker = getWorkerOrThrow(id);
        return workerMapper.toResponse(worker);
    }

    @Transactional
    public WorkerResponse create(CreateWorkerRequest request) {
        var agencyId = TenantContext.requireAgencyId();

        if (workerRepository.existsByAgencyIdAndInternalId(agencyId, request.internalId())) {
            throw new ConflictException("error.worker.internal_id_exists");
        }

        var worker = workerMapper.toEntity(request);
        worker.setAgencyId(agencyId);
        worker = workerRepository.save(worker);
        auditService.log(agencyId, currentUserId(), AuditEntityType.WORKER, worker.getId(),
                AuditAction.CREATED, null, snapshot(worker), null);
        return workerMapper.toResponse(worker);
    }

    @Transactional
    public WorkerResponse update(UUID id, UpdateWorkerRequest request) {
        var worker = getWorkerOrThrow(id);
        var previous = snapshot(worker);
        workerMapper.updateEntity(request, worker);
        worker = workerRepository.save(worker);
        auditService.log(worker.getAgencyId(), currentUserId(), AuditEntityType.WORKER, worker.getId(),
                AuditAction.UPDATED, previous, snapshot(worker), null);
        return workerMapper.toResponse(worker);
    }

    @Transactional
    public void delete(UUID id) {
        var worker = getWorkerOrThrow(id);
        var previous = snapshot(worker);
        worker.setStatus(WorkerStatus.DELETED);
        worker.setDeletedAt(Instant.now());
        workerRepository.save(worker);
        auditService.log(worker.getAgencyId(), currentUserId(), AuditEntityType.WORKER, worker.getId(),
                AuditAction.DELETED, previous, snapshot(worker), null);
    }

    private UUID currentUserId() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof CurrentUser currentUser) {
            return currentUser.userId();
        }
        return null;
    }

    private Map<String, Object> snapshot(Worker worker) {
        var map = new LinkedHashMap<String, Object>();
        map.put("status", worker.getStatus().name());
        map.put("internalId", worker.getInternalId());
        map.put("firstName", worker.getFirstName());
        map.put("lastName", worker.getLastName());
        map.put("gender", worker.getGender().name());
        return map;
    }

    private Worker getWorkerOrThrow(UUID id) {
        var agencyId = TenantContext.requireAgencyId();
        return workerRepository.findByIdAndAgencyIdAndStatusNot(id, agencyId, WorkerStatus.DELETED)
                .orElseThrow(() -> new NotFoundException("error.worker.not_found"));
    }
}
