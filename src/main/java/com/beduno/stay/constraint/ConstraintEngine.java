package com.beduno.stay.constraint;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
@RequiredArgsConstructor
public class ConstraintEngine {

    private final List<StayConstraint> constraints;

    public ConstraintResult evaluate(ConstraintContext ctx) {
        var hard = new ArrayList<HardViolation>();
        var soft = new ArrayList<SoftViolation>();
        constraints.forEach(c -> c.evaluate(ctx, hard, soft));
        return new ConstraintResult(List.copyOf(hard), List.copyOf(soft));
    }
}
