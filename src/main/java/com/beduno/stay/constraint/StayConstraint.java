package com.beduno.stay.constraint;

import java.util.List;

public interface StayConstraint {

    void evaluate(ConstraintContext ctx, List<HardViolation> hard, List<SoftViolation> soft);
}
