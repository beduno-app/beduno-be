package com.bedok.worker;

import com.bedok.common.exception.ConflictException;
import com.bedok.common.exception.NotFoundException;
import com.bedok.common.model.PageResponse;
import com.bedok.common.security.TenantContext;
import com.bedok.worker.dto.CreateWorkerRequest;
import com.bedok.worker.dto.UpdateWorkerRequest;
import com.bedok.worker.dto.WorkerResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class WorkerService {

    private final WorkerRepository workerRepository;
    private final WorkerMapper workerMapper;

    @Transactional(readOnly = true)
    public PageResponse<WorkerResponse> findAll(WorkerStatus status, Gender gender, String tag, String search, Pageable pageable) {
        var agencyId = TenantContext.requireAgencyId();

        var page = (tag != null)
                ? workerRepository.findAllByAgencyIdWithFiltersAndTag(
                        agencyId,
                        status != null ? status.name() : null,
                        gender != null ? gender.name() : null,
                        search, tag, pageable)
                : workerRepository.findAllByAgencyIdWithFilters(agencyId, status, gender, search, pageable);

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
        return workerMapper.toResponse(worker);
    }

    @Transactional
    public WorkerResponse update(UUID id, UpdateWorkerRequest request) {
        var worker = getWorkerOrThrow(id);
        workerMapper.updateEntity(request, worker);
        worker = workerRepository.save(worker);
        return workerMapper.toResponse(worker);
    }

    @Transactional
    public void delete(UUID id) {
        var worker = getWorkerOrThrow(id);
        worker.setStatus(WorkerStatus.DELETED);
        worker.setDeletedAt(Instant.now());
        workerRepository.save(worker);
    }

    private Worker getWorkerOrThrow(UUID id) {
        var agencyId = TenantContext.requireAgencyId();
        return workerRepository.findByIdAndAgencyIdAndStatusNot(id, agencyId, WorkerStatus.DELETED)
                .orElseThrow(() -> new NotFoundException("error.worker.not_found"));
    }
}
