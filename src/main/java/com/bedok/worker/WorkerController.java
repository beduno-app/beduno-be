package com.bedok.worker;

import com.bedok.common.model.PageResponse;
import com.bedok.worker.dto.CreateWorkerRequest;
import com.bedok.worker.dto.UpdateWorkerRequest;
import com.bedok.worker.dto.WorkerImportResult;
import com.bedok.worker.dto.WorkerResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/workers")
@RequiredArgsConstructor
public class WorkerController {

    private final WorkerService workerService;

    @GetMapping
    @PreAuthorize("hasAnyRole('AGENCY_ADMIN', 'AGENCY_PLANNER', 'PROPERTY_ADMIN', 'FRONT_DESK')")
    public ResponseEntity<PageResponse<WorkerResponse>> findAll(
            @RequestParam(required = false) WorkerStatus status,
            @RequestParam(required = false) Gender gender,
            @RequestParam(required = false) String tag,
            @RequestParam(required = false) String search,
            @PageableDefault(sort = "last_name", direction = Sort.Direction.ASC) Pageable pageable) {
        return ResponseEntity.ok(workerService.findAll(status, gender, tag, search, pageable));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('AGENCY_ADMIN', 'AGENCY_PLANNER', 'PROPERTY_ADMIN', 'FRONT_DESK')")
    public ResponseEntity<WorkerResponse> findById(@PathVariable UUID id) {
        return ResponseEntity.ok(workerService.findById(id));
    }

    @PostMapping
    @PreAuthorize("hasRole('AGENCY_ADMIN')")
    public ResponseEntity<WorkerResponse> create(@Valid @RequestBody CreateWorkerRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(workerService.create(request));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('AGENCY_ADMIN')")
    public ResponseEntity<WorkerResponse> update(@PathVariable UUID id,
                                                  @Valid @RequestBody UpdateWorkerRequest request) {
        return ResponseEntity.ok(workerService.update(id, request));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('AGENCY_ADMIN')")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        workerService.delete(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping(value = "/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasRole('AGENCY_ADMIN')")
    public ResponseEntity<WorkerImportResult> importCsv(@RequestParam("file") MultipartFile file) {
        return ResponseEntity.ok(workerService.importCsv(file));
    }
}
