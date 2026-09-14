package com.beduno.stay;

import java.time.LocalDate;

/**
 * Date helpers shared by the constraint engine and the stay service.
 *
 * <p>Stays are half-open periods {@code [dateFrom, dateTo)}; a null {@code dateTo} means the stay
 * is open-ended. Overlap queries need a concrete upper bound, and the obvious sentinel --
 * {@code LocalDate.MAX} -- cannot be used: Hibernate binds {@code LocalDate} through
 * {@code DateJdbcType}, i.e. {@code java.sql.Date.valueOf(...)} and
 * {@code PreparedStatement.setDate}, which overflows for year 999999999 and makes PostgreSQL
 * reject the statement with {@code date out of range}. Before this helper existed, every attempt
 * to create an open-ended stay ended in a 500.
 */
public final class StayDates {

    /**
     * Upper bound used for open-ended stays. Comfortably beyond any real housing contract and
     * well inside PostgreSQL's {@code date} range, so it binds like any other date.
     */
    public static final LocalDate OPEN_ENDED = LocalDate.of(9999, 12, 31);

    private StayDates() {
    }

    /** Returns {@code dateTo}, or {@link #OPEN_ENDED} when the stay has no planned end. */
    public static LocalDate effectiveEnd(LocalDate dateTo) {
        return dateTo != null ? dateTo : OPEN_ENDED;
    }
}
