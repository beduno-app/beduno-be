package com.beduno.stay.dto;

import java.util.UUID;

public record CheckInRequest(UUID roomId, String overrideReason) {
}
