package com.enterprise.risk.common.utils;

import com.enterprise.risk.common.event.RiskEvent;
import lombok.extern.slf4j.Slf4j;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 事件相关工具类
 * 提供字段提取、类型转换、IP解析、时间窗口计算等功能
 */
@Slf4j
public class EventUtils {

    private static final Pattern IP_PATTERN = Pattern.compile(
            "^((25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\\.){3}(25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)$"
    );

    private static final DateTimeFormatter DEFAULT_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private EventUtils() {
    }

    /**
     * 从事件中提取指定字段的值
     *
     * @param event 风险事件
     * @param fieldName 字段名，支持嵌套字段用点分隔（如 user.profile.age）
     * @return 字段值，不存在返回null
     */
    @SuppressWarnings("unchecked")
    public static Object extractField(RiskEvent event, String fieldName) {
        if (event == null || fieldName == null || fieldName.isEmpty()) {
            return null;
        }
        String[] parts = fieldName.split("\\.");
        Object current = null;
        for (int i = 0; i < parts.length; i++) {
            String part = parts[i];
            if (i == 0) {
                current = getTopLevelField(event, part);
            } else {
                if (current instanceof Map) {
                    current = ((Map<String, Object>) current).get(part);
                } else {
                    return null;
                }
            }
            if (current == null) {
                return null;
            }
        }
        return current;
    }

    /**
     * 获取事件顶层字段
     */
    private static Object getTopLevelField(RiskEvent event, String fieldName) {
        switch (fieldName) {
            case "event_id":
                return event.getEventId();
            case "event_type":
                return event.getEventType();
            case "business_line":
                return event.getBusinessLine();
            case "timestamp":
                return event.getTimestamp();
            case "entity_id":
                return event.getEntityId();
            case "entity_type":
                return event.getEntityType();
            case "source":
                return event.getSource();
            case "session_id":
                return event.getSessionId();
            case "ip":
                return event.getIp();
            case "user_id":
                return event.getUserId();
            default:
                return event.getAttributes().get(fieldName);
        }
    }

    /**
     * 提取字段并转换为字符串
     *
     * @param event 风险事件
     * @param fieldName 字段名
     * @return 字符串值，不存在返回null
     */
    public static String extractString(RiskEvent event, String fieldName) {
        Object value = extractField(event, fieldName);
        return value != null ? value.toString() : null;
    }

    /**
     * 提取字段并转换为Long
     *
     * @param event 风险事件
     * @param fieldName 字段名
     * @return Long值，转换失败返回null
     */
    public static Long extractLong(RiskEvent event, String fieldName) {
        Object value = extractField(event, fieldName);
        if (value == null) {
            return null;
        }
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        try {
            return Long.parseLong(value.toString());
        } catch (NumberFormatException e) {
            log.warn("字段 {} 转换为Long失败: {}", fieldName, value);
            return null;
        }
    }

    /**
     * 提取字段并转换为Double
     *
     * @param event 风险事件
     * @param fieldName 字段名
     * @return Double值，转换失败返回null
     */
    public static Double extractDouble(RiskEvent event, String fieldName) {
        Object value = extractField(event, fieldName);
        if (value == null) {
            return null;
        }
        if (value instanceof Number) {
            return ((Number) value).doubleValue();
        }
        try {
            return Double.parseDouble(value.toString());
        } catch (NumberFormatException e) {
            log.warn("字段 {} 转换为Double失败: {}", fieldName, value);
            return null;
        }
    }

    /**
     * 提取字段并转换为Integer
     *
     * @param event 风险事件
     * @param fieldName 字段名
     * @return Integer值，转换失败返回null
     */
    public static Integer extractInteger(RiskEvent event, String fieldName) {
        Object value = extractField(event, fieldName);
        if (value == null) {
            return null;
        }
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        try {
            return Integer.parseInt(value.toString());
        } catch (NumberFormatException e) {
            log.warn("字段 {} 转换为Integer失败: {}", fieldName, value);
            return null;
        }
    }

    /**
     * 提取字段并转换为Boolean
     *
     * @param event 风险事件
     * @param fieldName 字段名
     * @return Boolean值
     */
    public static Boolean extractBoolean(RiskEvent event, String fieldName) {
        Object value = extractField(event, fieldName);
        if (value == null) {
            return null;
        }
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        return Boolean.parseBoolean(value.toString());
    }

    /**
     * 验证IP地址格式是否合法
     *
     * @param ip IP地址
     * @return 是否合法
     */
    public static boolean isValidIp(String ip) {
        if (ip == null || ip.isEmpty()) {
            return false;
        }
        return IP_PATTERN.matcher(ip).matches();
    }

    /**
     * 解析IP地址为主机名
     *
     * @param ip IP地址
     * @return 主机名，解析失败返回原IP
     */
    public static String resolveIpToHostname(String ip) {
        if (!isValidIp(ip)) {
            return ip;
        }
        try {
            InetAddress inetAddress = InetAddress.getByName(ip);
            return inetAddress.getHostName();
        } catch (UnknownHostException e) {
            log.warn("IP {} 解析主机名失败", ip, e);
            return ip;
        }
    }

    /**
     * 将IP地址转换为Long整数
     *
     * @param ip IP地址
     * @return Long值，非法IP返回null
     */
    public static Long ipToLong(String ip) {
        if (!isValidIp(ip)) {
            return null;
        }
        String[] parts = ip.split("\\.");
        long result = 0;
        for (int i = 0; i < 4; i++) {
            result = (result << 8) | Long.parseLong(parts[i]);
        }
        return result;
    }

    /**
     * 将Long整数转换为IP地址
     *
     * @param ipLong IP长整型值
     * @return IP地址字符串
     */
    public static String longToIp(Long ipLong) {
        if (ipLong == null) {
            return null;
        }
        return ((ipLong >> 24) & 0xFF) + "."
                + ((ipLong >> 16) & 0xFF) + "."
                + ((ipLong >> 8) & 0xFF) + "."
                + (ipLong & 0xFF);
    }

    /**
     * 判断IP是否在指定网段内（CIDR格式）
     *
     * @param ip IP地址
     * @param cidr CIDR网段，如 192.168.1.0/24
     * @return 是否在网段内
     */
    public static boolean isIpInCidr(String ip, String cidr) {
        if (!isValidIp(ip) || cidr == null || !cidr.contains("/")) {
            return false;
        }
        String[] parts = cidr.split("/");
        String networkIp = parts[0];
        int prefixLength = Integer.parseInt(parts[1]);
        if (!isValidIp(networkIp) || prefixLength < 0 || prefixLength > 32) {
            return false;
        }
        Long ipLong = ipToLong(ip);
        Long networkLong = ipToLong(networkIp);
        if (ipLong == null || networkLong == null) {
            return false;
        }
        long mask = prefixLength == 0 ? 0 : (0xFFFFFFFFL << (32 - prefixLength));
        return (ipLong & mask) == (networkLong & mask);
    }

    /**
     * 格式化时间戳为默认格式字符串
     *
     * @param timestamp 时间戳（毫秒）
     * @return 格式化的时间字符串
     */
    public static String formatTimestamp(Long timestamp) {
        return formatTimestamp(timestamp, DEFAULT_FORMATTER, ZoneId.systemDefault());
    }

    /**
     * 格式化时间戳为指定格式字符串
     *
     * @param timestamp 时间戳（毫秒）
     * @param pattern 时间格式模式
     * @return 格式化的时间字符串
     */
    public static String formatTimestamp(Long timestamp, String pattern) {
        return formatTimestamp(timestamp, DateTimeFormatter.ofPattern(pattern), ZoneId.systemDefault());
    }

    /**
     * 格式化时间戳
     */
    private static String formatTimestamp(Long timestamp, DateTimeFormatter formatter, ZoneId zoneId) {
        if (timestamp == null) {
            return null;
        }
        LocalDateTime dateTime = LocalDateTime.ofInstant(Instant.ofEpochMilli(timestamp), zoneId);
        return dateTime.format(formatter);
    }

    /**
     * 计算事件时间与当前时间的差值（毫秒）
     *
     * @param event 风险事件
     * @return 时间差（毫秒）
     */
    public static long getTimeDiffFromNow(RiskEvent event) {
        if (event == null || event.getTimestamp() == null) {
            return 0;
        }
        return System.currentTimeMillis() - event.getTimestamp();
    }

    /**
     * 计算两个时间戳之间的窗口差值（按窗口大小对齐）
     *
     * @param timestamp1 时间戳1
     * @param timestamp2 时间戳2
     * @param windowSizeMs 窗口大小（毫秒）
     * @return 窗口差值
     */
    public static long getWindowDiff(Long timestamp1, Long timestamp2, long windowSizeMs) {
        if (timestamp1 == null || timestamp2 == null || windowSizeMs <= 0) {
            return 0;
        }
        long window1 = timestamp1 / windowSizeMs;
        long window2 = timestamp2 / windowSizeMs;
        return Math.abs(window1 - window2);
    }

    /**
     * 获取时间戳所在窗口的起始时间
     *
     * @param timestamp 时间戳
     * @param windowSizeMs 窗口大小（毫秒）
     * @return 窗口起始时间
     */
    public static long getWindowStart(Long timestamp, long windowSizeMs) {
        if (timestamp == null || windowSizeMs <= 0) {
            return 0;
        }
        return (timestamp / windowSizeMs) * windowSizeMs;
    }

    /**
     * 获取时间戳所在窗口的结束时间
     *
     * @param timestamp 时间戳
     * @param windowSizeMs 窗口大小（毫秒）
     * @return 窗口结束时间
     */
    public static long getWindowEnd(Long timestamp, long windowSizeMs) {
        return getWindowStart(timestamp, windowSizeMs) + windowSizeMs;
    }
}
