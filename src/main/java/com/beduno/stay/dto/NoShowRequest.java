package com.beduno.stay.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * @param noShowReason free-form tag; bounded to match {@code stays.no_show_reason
 *                     VARCHAR(100)} so an over-long value is a 400, not a 500
 */
public record NoShowRequest(@NotBlank @Size(max = 100) String noShowReason) {
}
