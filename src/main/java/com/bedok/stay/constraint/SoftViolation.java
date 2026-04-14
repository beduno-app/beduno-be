package com.bedok.stay.constraint;

import java.util.Map;

public record SoftViolation(String type, String message, Map<String, Object> params) implements Violation {
}
