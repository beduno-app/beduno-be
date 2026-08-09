package com.beduno.stay.dto;

import jakarta.validation.constraints.NotBlank;

public record NoShowRequest(@NotBlank String reasonTag) {
}
