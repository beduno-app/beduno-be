package com.beduno.stay;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;

@Component
@RequiredArgsConstructor
@Slf4j
public class StayScheduler {

    private final StayService stayService;
    private final Clock clock;

    /**
     * Promotes PLANNED stays that are due. The zone is pinned to the business zone rather than the
     * JVM default, which in the container is UTC -- the 06:00 cron was firing at 07:00 or 08:00
     * local time.
     */
    @Scheduled(cron = "${beduno.scheduler.arrival-transition-cron:0 0 6 * * *}",
            zone = "${beduno.time-zone}")
    public void transitionArrivalsToExpectedToday() {
        sweep("scheduled");
    }

    /**
     * The same sweep on start-up. The deployment is a single instance that is stopped when the API
     * is not in use, so a missed 06:00 run is routine rather than hypothetical -- and until this
     * ran, every affected stay was stuck: PLANNED cannot transition to CHECKED_IN, so check-in
     * returned 409 with a database update as the only way out.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void catchUpOnStartup() {
        sweep("startup");
    }

    private void sweep(String trigger) {
        var today = LocalDate.now(clock);
        var count = stayService.transitionPlannedToExpectedToday(today);
        if (count > 0) {
            log.info("Transitioned {} planned stays to EXPECTED_TODAY for {} ({} sweep)",
                    count, today, trigger);
        }
    }
}
