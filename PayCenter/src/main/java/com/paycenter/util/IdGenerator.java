package com.paycenter.util;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

public class IdGenerator {
    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    public static String generateTransactionId() {
        return "trans_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    public static String generateRefundId() {
        return "refund_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    public static String generateSettlementId() {
        return "settle_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    public static String generateStatId() {
        return "stat_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    public static String generateAccountId(String merchantId) {
        return "account_" + merchantId;
    }

    public static String generateChannelId() {
        return "channel_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    public static String generatePeriodId() {
        return "period_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    public static String generateOrderNo() {
        return LocalDateTime.now().format(DATE_TIME_FORMATTER) + UUID.randomUUID().toString().replace("-", "").substring(0, 6);
    }
}
