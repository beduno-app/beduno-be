package com.beduno.bed.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

public record BulkGenerateBedsRequest(
        // One save and one audit row per bed, all inside a single transaction. An unbounded count
        // lets any property admin exhaust the instance's 1 GB heap with a single request; no real
        // room comes anywhere near this ceiling.
        @Min(1) @Max(MAX_COUNT) int count
) {

    public static final int MAX_COUNT = 200;
}
