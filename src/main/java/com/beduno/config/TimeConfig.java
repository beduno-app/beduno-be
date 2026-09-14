package com.beduno.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.ZoneId;

/**
 * Pins "today" to the zone the agencies actually work in.
 *
 * <p>Every date-boundary decision in the system -- which stays are arriving today, who is in a bed
 * today, when the nightly sweep runs -- was taken with {@code LocalDate.now()} in the JVM default
 * zone. Nothing sets a zone in the Dockerfile or compose file, so in production that is UTC while
 * the business runs in Europe/Warsaw. For the one or two hours a day the two disagree, occupancy
 * reported the previous day's occupants and a night porter checking someone in after midnight was
 * told the stay was not due yet.
 *
 * <p>Instants are unaffected and stay in UTC: {@code TIMESTAMPTZ} columns mapped to {@link
 * java.time.Instant} are points in time, not calendar days, and are correct as they are.
 */
@Configuration
public class TimeConfig {

    @Bean
    public Clock clock(@Value("${beduno.time-zone}") String timeZone) {
        return Clock.system(ZoneId.of(timeZone));
    }
}
