package com.datamasker.application.scheduler;

import com.datamasker.application.service.BatchPrivacyService;
import com.datamasker.domain.privacy.batch.BatchRequestAccumulator;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class BatchFlushScheduler {

    private final BatchPrivacyService batchPrivacyService;
    private final BatchRequestAccumulator accumulator;

    @Scheduled(fixedDelay = 500)
    public void flushAccumulatorPeriodically() {
        if (accumulator.shouldFlush() || accumulator.getPendingItems() > 0) {
            batchPrivacyService.flushAccumulator();
        }
    }
}
