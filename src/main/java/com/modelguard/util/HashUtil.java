package com.modelguard.util;

import cn.hutool.core.util.HashUtil;

public final class HashUtil {

    private HashUtil() {
    }

    public static long fnvHash(String input) {
        return HashUtil.fnvHash(input);
    }

    public static long fnvHash(String... inputs) {
        return HashUtil.fnvHash(String.join(":", inputs));
    }

    public static int assignGroup(String userId, String experimentId, int groupCount) {
        long hash = fnvHash(userId, experimentId);
        return Math.abs((int) (hash % groupCount));
    }

    public static boolean isInTraffic(String userId, String experimentId, double trafficRatio) {
        if (trafficRatio <= 0) return false;
        if (trafficRatio >= 1) return true;
        long hash = fnvHash(userId, experimentId);
        double normalized = (double) Math.abs(hash % 10000) / 10000.0;
        return normalized < trafficRatio;
    }

    public static String assignGroup(String userId, String experimentId, java.util.List<String> groups) {
        int index = assignGroup(userId, experimentId, groups.size());
        return groups.get(index);
    }
}
