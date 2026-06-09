package com.cardgame.common.utils;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

public class IdGenerator {
    private static final AtomicLong ROOM_ID_COUNTER = new AtomicLong(100000);
    private static final String CHARACTERS = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";

    private IdGenerator() {
    }

    public static String generateUUID() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    public static String generateRoomId() {
        return "RM" + ROOM_ID_COUNTER.incrementAndGet();
    }

    public static String generateInviteCode() {
        StringBuilder sb = new StringBuilder(6);
        for (int i = 0; i < 6; i++) {
            int index = (int) (Math.random() * CHARACTERS.length());
            sb.append(CHARACTERS.charAt(index));
        }
        return sb.toString();
    }

    public static String generateBattleId() {
        return "BT" + System.currentTimeMillis() + ROOM_ID_COUNTER.incrementAndGet() % 1000;
    }

    public static String generatePlayerId() {
        return "PL" + ROOM_ID_COUNTER.incrementAndGet();
    }
}
