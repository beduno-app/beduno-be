package com.bedok.stay.constraint.impl;

import com.bedok.room.GenderRule;
import com.bedok.worker.Gender;
import com.bedok.stay.constraint.ConstraintContext;
import com.bedok.stay.constraint.HardViolation;
import com.bedok.stay.constraint.SoftViolation;
import com.bedok.stay.constraint.StayConstraint;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
public class GenderConstraint implements StayConstraint {

    @Override
    public void evaluate(ConstraintContext ctx, List<HardViolation> hard, List<SoftViolation> soft) {
        var rule = ctx.room().getGenderRule();
        var gender = ctx.worker().getGender();

        if (rule == GenderRule.ANY) {
            return;
        }

        boolean violates = (rule == GenderRule.MALE_ONLY && gender != Gender.MALE)
                || (rule == GenderRule.FEMALE_ONLY && gender != Gender.FEMALE);

        if (violates) {
            soft.add(new SoftViolation(
                    "GENDER_MISMATCH",
                    "constraint.room.gender_mismatch",
                    Map.of(
                            "roomName", ctx.room().getName(),
                            "genderRule", rule.name(),
                            "workerGender", gender.name()
                    )
            ));
        }
    }
}
