package com.beduno.stay.constraint;

import java.util.List;

public record ConstraintResult(List<HardViolation> hardViolations, List<SoftViolation> softViolations) {

    public boolean isAllowed() {
        return hardViolations.isEmpty();
    }

    public boolean hasWarnings() {
        return !softViolations.isEmpty();
    }
}
