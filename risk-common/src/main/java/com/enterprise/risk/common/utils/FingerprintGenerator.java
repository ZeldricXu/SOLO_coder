package com.enterprise.risk.common.utils;

import com.enterprise.risk.common.alert.AlertEvent;
import com.enterprise.risk.common.event.RiskEvent;
import lombok.extern.slf4j.Slf4j;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 告警指纹生成器
 * 基于MD5/SHA256哈希关键字段生成唯一指纹，用于告警去重和关联
 */
@Slf4j
public class FingerprintGenerator {

    /**
     * 哈希算法类型
     */
    public enum HashAlgorithm {
        MD5("MD5"),
        SHA256("SHA-256");

        private final String algorithm;

        HashAlgorithm(String algorithm) {
            this.algorithm = algorithm;
        }

        public String getAlgorithm() {
            return algorithm;
        }
    }

    /**
     * 默认指纹字段列表
     */
    private static final List<String> DEFAULT_FINGERPRINT_FIELDS = Arrays.asList(
            "rule_id",
            "entity_id",
            "entity_type",
            "business_line",
            "event_type"
    );

    private static final char[] HEX_ARRAY = "0123456789abcdef".toCharArray();

    private FingerprintGenerator() {
    }

    /**
     * 使用默认字段和SHA256算法生成告警指纹
     *
     * @param event 风险事件
     * @param ruleId 规则ID
     * @return 指纹字符串
     */
    public static String generate(RiskEvent event, String ruleId) {
        return generate(event, ruleId, DEFAULT_FINGERPRINT_FIELDS, HashAlgorithm.SHA256);
    }

    /**
     * 使用指定字段和SHA256算法生成告警指纹
     *
     * @param event 风险事件
     * @param ruleId 规则ID
     * @param fields 参与哈希的字段列表
     * @return 指纹字符串
     */
    public static String generate(RiskEvent event, String ruleId, List<String> fields) {
        return generate(event, ruleId, fields, HashAlgorithm.SHA256);
    }

    /**
     * 使用指定字段和算法生成告警指纹
     *
     * @param event 风险事件
     * @param ruleId 规则ID
     * @param fields 参与哈希的字段列表
     * @param algorithm 哈希算法
     * @return 指纹字符串
     */
    public static String generate(RiskEvent event, String ruleId, List<String> fields, HashAlgorithm algorithm) {
        if (event == null || fields == null || fields.isEmpty()) {
            throw new IllegalArgumentException("事件和字段列表不能为空");
        }
        List<String> values = new ArrayList<>();
        values.add(ruleId != null ? ruleId : "");
        for (String field : fields) {
            if ("rule_id".equals(field)) {
                continue;
            }
            Object value = EventUtils.extractField(event, field);
            values.add(value != null ? value.toString() : "");
        }
        String raw = values.stream()
                .filter(Objects::nonNull)
                .collect(Collectors.joining("|"));
        return hash(raw, algorithm);
    }

    /**
     * 基于告警事件生成指纹
     *
     * @param alertEvent 告警事件
     * @return 指纹字符串
     */
    public static String generateFromAlert(AlertEvent alertEvent) {
        return generateFromAlert(alertEvent, HashAlgorithm.SHA256);
    }

    /**
     * 基于告警事件和指定算法生成指纹
     *
     * @param alertEvent 告警事件
     * @param algorithm 哈希算法
     * @return 指纹字符串
     */
    public static String generateFromAlert(AlertEvent alertEvent, HashAlgorithm algorithm) {
        if (alertEvent == null) {
            throw new IllegalArgumentException("告警事件不能为空");
        }
        List<String> values = Arrays.asList(
                alertEvent.getRuleId() != null ? alertEvent.getRuleId() : "",
                alertEvent.getEntityId() != null ? alertEvent.getEntityId() : "",
                alertEvent.getEntityType() != null ? alertEvent.getEntityType() : "",
                alertEvent.getBusinessLine() != null ? alertEvent.getBusinessLine() : ""
        );
        String raw = String.join("|", values);
        return hash(raw, algorithm);
    }

    /**
     * 使用自定义键值对列表生成指纹
     *
     * @param keyValues 键值对列表，按顺序拼接
     * @return 指纹字符串
     */
    public static String generateFromPairs(List<String> keyValues) {
        return generateFromPairs(keyValues, HashAlgorithm.SHA256);
    }

    /**
     * 使用自定义键值对列表和指定算法生成指纹
     *
     * @param keyValues 键值对列表
     * @param algorithm 哈希算法
     * @return 指纹字符串
     */
    public static String generateFromPairs(List<String> keyValues, HashAlgorithm algorithm) {
        if (keyValues == null || keyValues.isEmpty()) {
            throw new IllegalArgumentException("键值对列表不能为空");
        }
        String raw = String.join("|", keyValues);
        return hash(raw, algorithm);
    }

    /**
     * 生成带时间窗口的指纹（用于滑动窗口去重）
     *
     * @param event 风险事件
     * @param ruleId 规则ID
     * @param windowSizeMs 窗口大小（毫秒）
     * @return 指纹字符串
     */
    public static String generateWithWindow(RiskEvent event, String ruleId, long windowSizeMs) {
        return generateWithWindow(event, ruleId, DEFAULT_FINGERPRINT_FIELDS, windowSizeMs, HashAlgorithm.SHA256);
    }

    /**
     * 生成带时间窗口的指纹
     *
     * @param event 风险事件
     * @param ruleId 规则ID
     * @param fields 参与哈希的字段列表
     * @param windowSizeMs 窗口大小（毫秒）
     * @param algorithm 哈希算法
     * @return 指纹字符串
     */
    public static String generateWithWindow(RiskEvent event, String ruleId, List<String> fields,
                                            long windowSizeMs, HashAlgorithm algorithm) {
        if (event == null || fields == null || fields.isEmpty()) {
            throw new IllegalArgumentException("事件和字段列表不能为空");
        }
        long windowStart = EventUtils.getWindowStart(event.getTimestamp(), windowSizeMs);
        List<String> values = new ArrayList<>();
        values.add(ruleId != null ? ruleId : "");
        values.add(String.valueOf(windowStart));
        for (String field : fields) {
            if ("rule_id".equals(field)) {
                continue;
            }
            Object value = EventUtils.extractField(event, field);
            values.add(value != null ? value.toString() : "");
        }
        String raw = String.join("|", values);
        return hash(raw, algorithm);
    }

    /**
     * 对字符串进行哈希计算
     *
     * @param input 输入字符串
     * @param algorithm 哈希算法
     * @return 十六进制哈希字符串
     */
    public static String hash(String input, HashAlgorithm algorithm) {
        if (input == null) {
            input = "";
        }
        try {
            MessageDigest digest = MessageDigest.getInstance(algorithm.getAlgorithm());
            byte[] hashBytes = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return bytesToHex(hashBytes);
        } catch (NoSuchAlgorithmException e) {
            log.error("哈希算法 {} 不可用", algorithm.getAlgorithm(), e);
            throw new RuntimeException("哈希算法不可用: " + algorithm.getAlgorithm(), e);
        }
    }

    /**
     * 使用MD5哈希
     *
     * @param input 输入字符串
     * @return MD5哈希字符串
     */
    public static String md5(String input) {
        return hash(input, HashAlgorithm.MD5);
    }

    /**
     * 使用SHA256哈希
     *
     * @param input 输入字符串
     * @return SHA256哈希字符串
     */
    public static String sha256(String input) {
        return hash(input, HashAlgorithm.SHA256);
    }

    /**
     * 字节数组转换为十六进制字符串
     *
     * @param bytes 字节数组
     * @return 十六进制字符串
     */
    private static String bytesToHex(byte[] bytes) {
        char[] hexChars = new char[bytes.length * 2];
        for (int j = 0; j < bytes.length; j++) {
            int v = bytes[j] & 0xFF;
            hexChars[j * 2] = HEX_ARRAY[v >>> 4];
            hexChars[j * 2 + 1] = HEX_ARRAY[v & 0x0F];
        }
        return new String(hexChars);
    }
}
