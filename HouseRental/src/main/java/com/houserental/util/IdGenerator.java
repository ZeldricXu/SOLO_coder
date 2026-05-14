package com.houserental.util;

import java.util.UUID;

public class IdGenerator {

    private IdGenerator() {
    }

    public static String generateHouseId() {
        return "house_" + UUID.randomUUID().toString().substring(0, 8);
    }

    public static String generateLandlordId() {
        return "landlord_" + UUID.randomUUID().toString().substring(0, 8);
    }

    public static String generateTenantId() {
        return "tenant_" + UUID.randomUUID().toString().substring(0, 8);
    }

    public static String generateApplicationId() {
        return "app_" + UUID.randomUUID().toString().substring(0, 8);
    }

    public static String generateContractId() {
        return "contract_" + UUID.randomUUID().toString().substring(0, 8);
    }

    public static String generatePaymentId() {
        return "payment_" + UUID.randomUUID().toString().substring(0, 8);
    }

    public static String generateStatId() {
        return "stat_" + UUID.randomUUID().toString().substring(0, 8);
    }

    public static String generateHistoryId() {
        return "history_" + UUID.randomUUID().toString().substring(0, 8);
    }
}
