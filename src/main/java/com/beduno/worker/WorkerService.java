package com.beduno.worker;

import com.beduno.common.model.SortFields;
import com.beduno.audit.AuditAction;
import com.beduno.audit.AuditEntityType;
import com.beduno.audit.AuditService;
import com.beduno.common.exception.ConflictException;
import com.beduno.common.exception.NotFoundException;
import com.beduno.common.model.PageResponse;
import com.beduno.common.security.CurrentUser;
import com.beduno.common.security.TenantContext;
import com.beduno.stay.StayRepository;
import com.beduno.stay.StayStatus;
import com.beduno.worker.dto.CreateWorkerRequest;
import com.beduno.worker.dto.UpdateWorkerRequest;
import com.beduno.worker.dto.WorkerImportResult;
import com.beduno.worker.dto.WorkerImportResult.WorkerImportError;
import com.beduno.worker.dto.WorkerResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class WorkerService {

    /**
     * The fields a client may sort workers by, mapped to the columns the native query orders by.
     */
    private static final Map<String, String> SORTABLE = Map.of(
            "firstName", "first_name",
            "lastName", "last_name",
            "internalId", "internal_id",
            "status", "status",
            "gender", "gender",
            "nationality", "nationality",
            "dateOfBirth", "date_of_birth",
            "createdAt", "created_at",
            "updatedAt", "updated_at");

    /** The statuses in which a stay still reserves a bed, so the worker cannot be removed. */
    private static final List<StayStatus> ACTIVE_STAY_STATUSES = List.of(
            StayStatus.PLANNED, StayStatus.EXPECTED_TODAY, StayStatus.CHECKED_IN);

    private final WorkerRepository workerRepository;
    private final WorkerMapper workerMapper;
    private final AuditService auditService;
    private final StayRepository stayRepository;

    @Transactional(readOnly = true)
    public PageResponse<WorkerResponse> findAll(WorkerStatus status, Gender gender, String tag, String search, Pageable pageable) {
        var agencyId = TenantContext.requireAgencyId();
        var sorted = SortFields.translate(pageable, SORTABLE);

        var page = (tag != null)
                ? workerRepository.findAllByAgencyIdWithFiltersAndTag(
                        agencyId,
                        status != null ? status.name() : null,
                        gender != null ? gender.name() : null,
                        search, tag, sorted)
                : workerRepository.findAllByAgencyIdWithFilters(
                        agencyId,
                        status != null ? status.name() : null,
                        gender != null ? gender.name() : null,
                        search, sorted);

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

    /**
     * Soft delete, guarded by the worker's active stays.
     *
     * <p>Flipping the status alone left those stays fully active: they still occupied their beds
     * for the constraint engine, so the bed stayed reserved and auto-assign skipped it forever,
     * while every stay operation that loads the worker -- update, check-in, move -- began failing
     * with "worker not found". The stay could be neither used nor fixed, and nothing told the
     * admin any of it.
     */
    @Transactional
    public void delete(UUID id) {
        var worker = getWorkerOrThrow(id);

        var activeStays = stayRepository.countActiveStaysForWorker(
                worker.getId(), worker.getAgencyId(), ACTIVE_STAY_STATUSES);
        if (activeStays > 0) {
            throw new ConflictException("error.worker.has_active_stays");
        }

        var previous = snapshot(worker);
        worker.setStatus(WorkerStatus.DELETED);
        worker.setDeletedAt(Instant.now());
        workerRepository.save(worker);
        auditService.log(worker.getAgencyId(), currentUserId(), AuditEntityType.WORKER, worker.getId(),
                AuditAction.DELETED, previous, snapshot(worker), null);
    }

    @Transactional
    public WorkerImportResult importCsv(MultipartFile file) {
        var agencyId = TenantContext.requireAgencyId();
        var actorId = currentUserId();
        int created = 0;
        int skipped = 0;
        var errors = new ArrayList<WorkerImportError>();

        try (var reader = new BufferedReader(new InputStreamReader(file.getInputStream(), StandardCharsets.UTF_8))) {
            var header = reader.readLine();
            if (header == null) {
                return new WorkerImportResult(0, 0, 0, List.of());
            }

            String line;
            int row = 1;
            while ((line = reader.readLine()) != null) {
                row++;
                line = line.trim();
                if (line.isEmpty()) {
                    continue;
                }
                try {
                    var cols = parseCsvLine(line);
                    if (cols.length < 4) {
                        errors.add(new WorkerImportError(row, null, "error.worker.import.too_few_columns"));
                        continue;
                    }

                    var internalId = col(cols, 0);
                    var firstName  = col(cols, 1);
                    var lastName   = col(cols, 2);
                    var genderStr  = col(cols, 3);

                    if (internalId.isBlank() || firstName.isBlank() || lastName.isBlank() || genderStr.isBlank()) {
                        errors.add(new WorkerImportError(row, internalId.isBlank() ? null : internalId,
                                "error.worker.import.required_field_missing"));
                        continue;
                    }

                    Gender gender;
                    try {
                        gender = Gender.valueOf(genderStr.toUpperCase());
                    } catch (IllegalArgumentException e) {
                        errors.add(new WorkerImportError(row, internalId, "error.worker.import.invalid_gender"));
                        continue;
                    }

                    if (workerRepository.existsByAgencyIdAndInternalId(agencyId, internalId)) {
                        skipped++;
                        continue;
                    }

                    var worker = new Worker();
                    worker.setAgencyId(agencyId);
                    worker.setInternalId(internalId);
                    worker.setFirstName(firstName);
                    worker.setLastName(lastName);
                    worker.setGender(gender);
                    worker.setNationality(col(cols, 4));
                    worker.setPhone(col(cols, 5));
                    worker.setEmail(col(cols, 6));
                    var dobStr = col(cols, 7);
                    if (!dobStr.isBlank()) {
                        worker.setDateOfBirth(LocalDate.parse(dobStr));
                    }
                    var tagsStr = col(cols, 8);
                    if (!tagsStr.isBlank()) {
                        worker.setTags(Arrays.stream(tagsStr.split(";"))
                                .map(String::trim).filter(t -> !t.isBlank()).toArray(String[]::new));
                    }
                    worker.setNotes(col(cols, 9));
                    worker.setStatus(WorkerStatus.ACTIVE);
                    worker = workerRepository.save(worker);
                    auditService.log(agencyId, actorId, AuditEntityType.WORKER, worker.getId(),
                            AuditAction.CREATED, null, snapshot(worker), "bulk_import");
                    created++;
                } catch (Exception e) {
                    log.warn("Error importing worker at row {}: {}", row, e.getMessage());
                    errors.add(new WorkerImportError(row, null, "error.worker.import.row_failed"));
                }
            }
        } catch (Exception e) {
            throw new com.beduno.common.exception.ValidationException("error.worker.import.file_unreadable");
        }

        return new WorkerImportResult(created, skipped, errors.size(), List.copyOf(errors));
    }

    private String[] parseCsvLine(String line) {
        var result = new ArrayList<String>();
        var sb = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                inQuotes = !inQuotes;
            } else if (c == ',' && !inQuotes) {
                result.add(sb.toString().trim());
                sb.setLength(0);
            } else {
                sb.append(c);
            }
        }
        result.add(sb.toString().trim());
        return result.toArray(String[]::new);
    }

    private String col(String[] cols, int index) {
        return index < cols.length ? cols[index].strip() : "";
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
