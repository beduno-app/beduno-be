package com.bedok.worker;

import com.bedok.common.model.PageResponse;
import com.bedok.worker.dto.CreateWorkerRequest;
import com.bedok.worker.dto.UpdateWorkerRequest;
import com.bedok.worker.dto.WorkerImportResult;
import com.bedok.worker.dto.WorkerResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
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

@Tag(name = "Workers", description = "Worker CRUD and CSV import")
@RestController
@RequestMapping("/api/v1/workers")
@RequiredArgsConstructor
public class WorkerController {

    private final WorkerService workerService;

    @Operation(summary = "List workers", description = "Paginated list with optional filters: status, gender, tag, name search")
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

    @Operation(summary = "Get worker by ID")
    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('AGENCY_ADMIN', 'AGENCY_PLANNER', 'PROPERTY_ADMIN', 'FRONT_DESK')")
    public ResponseEntity<WorkerResponse> findById(@PathVariable UUID id) {
        return ResponseEntity.ok(workerService.findById(id));
    }

    @Operation(summary = "Create worker")
    @ApiResponse(responseCode = "201", description = "Worker created")
    @PostMapping
    @PreAuthorize("hasRole('AGENCY_ADMIN')")
    public ResponseEntity<WorkerResponse> create(@Valid @RequestBody CreateWorkerRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(workerService.create(request));
    }

    @Operation(summary = "Update worker")
    @PutMapping("/{id}")
    @PreAuthorize("hasRole('AGENCY_ADMIN')")
    public ResponseEntity<WorkerResponse> update(@PathVariable UUID id,
                                                  @Valid @RequestBody UpdateWorkerRequest request) {
        return ResponseEntity.ok(workerService.update(id, request));
    }

    @Operation(summary = "Delete worker", description = "Soft-deletes the worker")
    @ApiResponse(responseCode = "204", description = "Worker deleted")
    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('AGENCY_ADMIN')")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        workerService.delete(id);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Import workers from CSV",
            description = "Parse and create workers from a CSV file. Returns per-row summary: created/skipped/errors")
    @PostMapping(value = "/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasRole('AGENCY_ADMIN')")
    public ResponseEntity<WorkerImportResult> importCsv(@RequestParam("file") MultipartFile file) {
        return ResponseEntity.ok(workerService.importCsv(file));
    }
}
