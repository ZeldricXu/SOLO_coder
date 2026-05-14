package com.parking.util;

import java.util.UUID;

public class IdGenerator {
    
    private IdGenerator() {
    }

    public static String generateParkingId() {
        return "parking_" + UUID.randomUUID().toString().substring(0, 8);
    }

    public static String generateSpaceId() {
        return "space_" + UUID.randomUUID().toString().substring(0, 8);
    }

    public static String generateVehicleId() {
        return "vehicle_" + UUID.randomUUID().toString().substring(0, 8);
    }

    public static String generateEntryId() {
        return "entry_" + UUID.randomUUID().toString().substring(0, 8);
    }

    public static String generateExitId() {
        return "exit_" + UUID.randomUUID().toString().substring(0, 8);
    }

    public static String generateSettlementId() {
        return "settlement_" + UUID.randomUUID().toString().substring(0, 8);
    }

    public static String generateReserveId() {
        return "reserve_" + UUID.randomUUID().toString().substring(0, 8);
    }

    public static String generateStatId() {
        return "stat_" + UUID.randomUUID().toString().substring(0, 8);
    }
}
