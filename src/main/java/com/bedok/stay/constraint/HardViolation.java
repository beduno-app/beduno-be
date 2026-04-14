package com.bedok.stay.constraint;

import java.util.Map;

public record HardViolation(String type, String message, Map<String, Object> params) implements Violation {
}
