package com.eventticket.builder;

public class MockConfig {

    public static final int VIP_LOCK_TIMEOUT_SECONDS = 300;
    public static final int REGULAR_LOCK_TIMEOUT_SECONDS = 900;

    public static final int SMALL_EVENT_RETRY_COUNT = 2;
    public static final int MEDIUM_EVENT_RETRY_COUNT = 3;
    public static final int LARGE_EVENT_RETRY_COUNT = 5;

    public static final int SMALL_EVENT_THRESHOLD = 1000;
    public static final int LARGE_EVENT_THRESHOLD = 10000;

    public static final double REFUND_FEE_RATE_EARLY = 0.10;
    public static final double REFUND_FEE_RATE_NORMAL = 0.30;
    public static final double REFUND_FEE_RATE_LATE = 0.50;

    public static final int DAYS_EARLY_REFUND = 7;
    public static final int DAYS_NORMAL_REFUND = 3;

    public static int getLockTimeout(String seatSection) {
        return "VIP".equalsIgnoreCase(seatSection) ? VIP_LOCK_TIMEOUT_SECONDS : REGULAR_LOCK_TIMEOUT_SECONDS;
    }

    public static int getRetryCount(int eventCapacity) {
        if (eventCapacity <= SMALL_EVENT_THRESHOLD) {
            return SMALL_EVENT_RETRY_COUNT;
        } else if (eventCapacity >= LARGE_EVENT_THRESHOLD) {
            return LARGE_EVENT_RETRY_COUNT;
        }
        return MEDIUM_EVENT_RETRY_COUNT;
    }

    public static double getRefundFeeRate(long daysBeforeEvent) {
        if (daysBeforeEvent >= DAYS_EARLY_REFUND) {
            return REFUND_FEE_RATE_EARLY;
        } else if (daysBeforeEvent >= DAYS_NORMAL_REFUND) {
            return REFUND_FEE_RATE_NORMAL;
        }
        return REFUND_FEE_RATE_LATE;
    }
}
