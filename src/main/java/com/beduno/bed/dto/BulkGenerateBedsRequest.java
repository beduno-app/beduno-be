package com.beduno.bed.dto;

import jakarta.validation.constraints.Min;

public record BulkGenerateBedsRequest(
        @Min(1) int count
) {}
