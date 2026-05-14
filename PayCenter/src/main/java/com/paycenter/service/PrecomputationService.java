package com.paycenter.service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.LocalTime;

public interface PrecomputationService {
    Duration calculatePrecomputationTime(int merchantCount);
    LocalTime getPrecomputationStartTime();
    LocalDateTime calculateNextPrecomputationTime();
    long getActiveMerchantCount();
    int getCurrentPrecomputationMinutes();
    void refreshPrecomputationSchedule();
}
