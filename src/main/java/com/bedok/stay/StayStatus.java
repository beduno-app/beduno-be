package com.bedok.stay;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

public enum StayStatus {
    PLANNED,
    EXPECTED_TODAY,
    CHECKED_IN,
    CHECKED_OUT,
    CANCELLED,
    NO_SHOW;

    private static final Map<StayStatus, Set<StayStatus>> VALID_TRANSITIONS;

    static {
        VALID_TRANSITIONS = new EnumMap<>(StayStatus.class);
        VALID_TRANSITIONS.put(PLANNED, EnumSet.of(EXPECTED_TODAY, CANCELLED));
        VALID_TRANSITIONS.put(EXPECTED_TODAY, EnumSet.of(CHECKED_IN, NO_SHOW, CANCELLED));
        VALID_TRANSITIONS.put(CHECKED_IN, EnumSet.of(CHECKED_OUT));
        VALID_TRANSITIONS.put(CHECKED_OUT, EnumSet.noneOf(StayStatus.class));
        VALID_TRANSITIONS.put(CANCELLED, EnumSet.noneOf(StayStatus.class));
        VALID_TRANSITIONS.put(NO_SHOW, EnumSet.noneOf(StayStatus.class));
    }

    public boolean canTransitionTo(StayStatus target) {
        return VALID_TRANSITIONS.getOrDefault(this, EnumSet.noneOf(StayStatus.class)).contains(target);
    }
}
