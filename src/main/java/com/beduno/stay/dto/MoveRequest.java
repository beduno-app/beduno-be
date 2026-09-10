package com.beduno.stay.dto;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record MoveRequest(@NotNull UUID targetRoomId, UUID targetBedId, String overrideReason) {
}
