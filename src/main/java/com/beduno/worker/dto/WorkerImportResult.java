package com.beduno.worker.dto;

import java.util.List;

public record WorkerImportResult(
        int created,
        int skipped,
        int errors,
        List<WorkerImportError> errorDetails
) {
    public record WorkerImportError(int row, String internalId, String reason) {}
}
