package com.beduno.stay.dto;

import java.util.UUID;

public record CheckInRequest(UUID roomId, UUID bedId, String overrideReason) {
}
