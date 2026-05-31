package com.web3platform.addressmanagement.util;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

public class Bip44PathUtil {

    private static final Pattern PATH_PATTERN = Pattern.compile("^m(\\/\\d+'?)*$");

    private static final int HARDENED_OFFSET = 0x80000000;

    public static List<Integer> parsePath(String path) {
        if (path == null || path.isEmpty()) {
            throw new IllegalArgumentException("Path cannot be null or empty");
        }

        if (!PATH_PATTERN.matcher(path).matches()) {
            throw new IllegalArgumentException("Invalid BIP44 path format: " + path);
        }

        List<Integer> segments = new ArrayList<>();
        String[] parts = path.split("/");

        for (int i = 1; i < parts.length; i++) {
            String part = parts[i];
            boolean hardened = part.endsWith("'");
            int value = Integer.parseInt(hardened ? part.substring(0, part.length() - 1) : part);

            if (hardened) {
                value |= HARDENED_OFFSET;
            }

            segments.add(value);
        }

        return segments;
    }

    public static String buildPath(int coinType, int account, int change, int index) {
        return String.format("m/44'/%d'/%d'/%d/%d", coinType, account, change, index);
    }

    public static int getCoinType(String chainType) {
        return switch (chainType.toUpperCase()) {
            case "BTC" -> 0;
            case "ETH", "BSC", "POLYGON", "MATIC" -> 60;
            case "LTC" -> 2;
            case "DOGE" -> 3;
            case "SOL" -> 501;
            case "ADA" -> 1815;
            case "DOT" -> 354;
            default -> throw new IllegalArgumentException("Unsupported chain type: " + chainType);
        };
    }

    public static String buildStandardPath(String chainType, int account, int index) {
        int coinType = getCoinType(chainType);
        return buildPath(coinType, account, 0, index);
    }

    public static boolean isHardened(int value) {
        return (value & HARDENED_OFFSET) != 0;
    }

    public static int unharden(int value) {
        return value & ~HARDENED_OFFSET;
    }
}
