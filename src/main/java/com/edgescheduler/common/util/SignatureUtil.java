package com.edgescheduler.common.util;

import cn.hutool.crypto.SecureUtil;
import cn.hutool.crypto.digest.HMac;
import cn.hutool.crypto.digest.HmacAlgorithm;
import java.util.Map;
import java.util.TreeMap;

public class SignatureUtil {

    private static final String SIGNATURE_KEY = "edge_scheduler_secret_key_2026";

    public static String generateSignature(Map<String, Object> params) {
        TreeMap<String, Object> sortedParams = new TreeMap<>(params);
        StringBuilder sb = new StringBuilder();
        sortedParams.forEach((key, value) -> {
            if (!"signature".equals(key) {
                sb.append(key).append("=").append(value).append("&");
            }
        });
        sb.append("key=").append(SIGNATURE_KEY);
        return SecureUtil.md5(sb.toString());
    }

    public static boolean validateSignature(Map<String, Object> params, String signature) {
        String generated = generateSignature(params);
        return generated.equalsIgnoreCase(signature);
    }

    public static String hmacSign(String data) {
        HMac hMac = new HMac(HmacAlgorithm.HmacSHA256, SIGNATURE_KEY.getBytes());
        return hMac.digestHex(data);
    }

    public static long getCurrentTimestamp() {
        return System.currentTimeMillis() / 1000;
    }

    public static boolean validateTimestamp(long timestamp, int expireSeconds) {
        long current = getCurrentTimestamp();
        return Math.abs(current - timestamp) <= expireSeconds;
    }
}
