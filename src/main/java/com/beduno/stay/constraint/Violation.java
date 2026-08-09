package com.beduno.stay.constraint;

import java.util.Map;

public sealed interface Violation permits HardViolation, SoftViolation {

    String type();

    String message();

    Map<String, Object> params();
}
