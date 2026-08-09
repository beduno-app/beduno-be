package com.beduno.stay;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

@Component
@RequiredArgsConstructor
@Slf4j
public class StayScheduler {

    private final StayService stayService;

    @Scheduled(cron = "${bedok.scheduler.arrival-transition-cron:0 0 6 * * *}")
    public void transitionArrivalsToExpectedToday() {
        var today = LocalDate.now();
        var count = stayService.transitionPlannedToExpectedToday(today);
        log.info("Transitioned {} planned stays to EXPECTED_TODAY for {}", count, today);
    }
}
