package com.enterprise.risk.common.utils;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.io.Serializable;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * 时间窗口计算工具
 * 提供滑动窗口、滚动窗口计算功能
 */
@Slf4j
public class TimeWindowUtils {

    /**
     * 窗口类型
     */
    public enum WindowType {
        TUMBLING,
        SLIDING,
        SESSION
    }

    /**
     * 窗口信息
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class WindowInfo implements Serializable {
        private Long windowStart;
        private Long windowEnd;
        private String windowKey;
        private Integer windowIndex;
    }

    /**
     * 常用时间常量（毫秒）
     */
    public static final long ONE_SECOND = 1000L;
    public static final long ONE_MINUTE = 60 * ONE_SECOND;
    public static final long FIVE_MINUTES = 5 * ONE_MINUTE;
    public static final long TEN_MINUTES = 10 * ONE_MINUTE;
    public static final long FIFTEEN_MINUTES = 15 * ONE_MINUTE;
    public static final long THIRTY_MINUTES = 30 * ONE_MINUTE;
    public static final long ONE_HOUR = 60 * ONE_MINUTE;
    public static final long TWO_HOURS = 2 * ONE_HOUR;
    public static final long SIX_HOURS = 6 * ONE_HOUR;
    public static final long TWELVE_HOURS = 12 * ONE_HOUR;
    public static final long ONE_DAY = 24 * ONE_HOUR;
    public static final long ONE_WEEK = 7 * ONE_DAY;

    private TimeWindowUtils() {
    }

    /**
     * 获取滚动窗口信息
     * 滚动窗口：固定大小，不重叠
     *
     * @param timestamp 时间戳
     * @param windowSizeMs 窗口大小（毫秒）
     * @return 窗口信息
     */
    public static WindowInfo getTumblingWindow(Long timestamp, long windowSizeMs) {
        if (timestamp == null || windowSizeMs <= 0) {
            throw new IllegalArgumentException("时间戳和窗口大小必须有效");
        }
        long start = (timestamp / windowSizeMs) * windowSizeMs;
        long end = start + windowSizeMs;
        long index = timestamp / windowSizeMs;
        return WindowInfo.builder()
                .windowStart(start)
                .windowEnd(end)
                .windowKey("tumbling_" + windowSizeMs + "_" + start)
                .windowIndex((int) index)
                .build();
    }

    /**
     * 获取滑动窗口信息
     * 滑动窗口：固定大小，按步长滑动，可能重叠
     *
     * @param timestamp 时间戳
     * @param windowSizeMs 窗口大小（毫秒）
     * @param slideStepMs 滑动步长（毫秒）
     * @return 窗口信息列表（一个时间点可能属于多个滑动窗口）
     */
    public static List<WindowInfo> getSlidingWindows(Long timestamp, long windowSizeMs, long slideStepMs) {
        if (timestamp == null || windowSizeMs <= 0 || slideStepMs <= 0) {
            throw new IllegalArgumentException("时间戳、窗口大小和滑动步长必须有效");
        }
        List<WindowInfo> windows = new ArrayList<>();
        long slideStart = (timestamp / slideStepMs) * slideStepMs;
        long firstWindowStart = slideStart - (windowSizeMs - slideStepMs);
        if (windowSizeMs <= slideStepMs) {
            firstWindowStart = slideStart;
        }
        for (long start = firstWindowStart; start <= slideStart; start += slideStepMs) {
            long end = start + windowSizeMs;
            if (timestamp >= start && timestamp < end) {
                long index = start / slideStepMs;
                windows.add(WindowInfo.builder()
                        .windowStart(start)
                        .windowEnd(end)
                        .windowKey("sliding_" + windowSizeMs + "_" + slideStepMs + "_" + start)
                        .windowIndex((int) index)
                        .build());
            }
        }
        return windows;
    }

    /**
     * 获取指定时间范围内所有滚动窗口
     *
     * @param startTimeMs 起始时间
     * @param endTimeMs 结束时间
     * @param windowSizeMs 窗口大小（毫秒）
     * @return 窗口信息列表
     */
    public static List<WindowInfo> getTumblingWindowsInRange(Long startTimeMs, Long endTimeMs, long windowSizeMs) {
        if (startTimeMs == null || endTimeMs == null || startTimeMs > endTimeMs || windowSizeMs <= 0) {
            throw new IllegalArgumentException("参数无效");
        }
        List<WindowInfo> windows = new ArrayList<>();
        WindowInfo firstWindow = getTumblingWindow(startTimeMs, windowSizeMs);
        long currentStart = firstWindow.getWindowStart();
        int index = 0;
        while (currentStart < endTimeMs) {
            windows.add(WindowInfo.builder()
                    .windowStart(currentStart)
                    .windowEnd(currentStart + windowSizeMs)
                    .windowKey("tumbling_" + windowSizeMs + "_" + currentStart)
                    .windowIndex(index++)
                    .build());
            currentStart += windowSizeMs;
        }
        return windows;
    }

    /**
     * 判断两个时间戳是否在同一个滚动窗口内
     *
     * @param timestamp1 时间戳1
     * @param timestamp2 时间戳2
     * @param windowSizeMs 窗口大小（毫秒）
     * @return 是否在同一窗口
     */
    public static boolean isInSameTumblingWindow(Long timestamp1, Long timestamp2, long windowSizeMs) {
        if (timestamp1 == null || timestamp2 == null || windowSizeMs <= 0) {
            return false;
        }
        return (timestamp1 / windowSizeMs) == (timestamp2 / windowSizeMs);
    }

    /**
     * 获取两个时间戳之间的窗口数
     *
     * @param startTimeMs 起始时间
     * @param endTimeMs 结束时间
     * @param windowSizeMs 窗口大小（毫秒）
     * @return 窗口数量
     */
    public static int getWindowCount(Long startTimeMs, Long endTimeMs, long windowSizeMs) {
        if (startTimeMs == null || endTimeMs == null || windowSizeMs <= 0) {
            return 0;
        }
        long diff = Math.abs(endTimeMs - startTimeMs);
        return (int) (diff / windowSizeMs) + 1;
    }

    /**
     * 获取当前时间窗口的剩余时间（毫秒）
     *
     * @param timestamp 当前时间戳
     * @param windowSizeMs 窗口大小（毫秒）
     * @return 剩余毫秒数
     */
    public static long getRemainingTimeInWindow(Long timestamp, long windowSizeMs) {
        if (timestamp == null || windowSizeMs <= 0) {
            return 0;
        }
        long windowEnd = getTumblingWindow(timestamp, windowSizeMs).getWindowEnd();
        return windowEnd - timestamp;
    }

    /**
     * 获取当前时间窗口已过的时间（毫秒）
     *
     * @param timestamp 当前时间戳
     * @param windowSizeMs 窗口大小（毫秒）
     * @return 已过毫秒数
     */
    public static long getElapsedTimeInWindow(Long timestamp, long windowSizeMs) {
        if (timestamp == null || windowSizeMs <= 0) {
            return 0;
        }
        long windowStart = getTumblingWindow(timestamp, windowSizeMs).getWindowStart();
        return timestamp - windowStart;
    }

    /**
     * 获取会话窗口信息
     * 会话窗口：基于事件间隔划分，超过间隔则开启新会话
     *
     * @param eventTimestamps 事件时间戳列表（需已排序）
     * @param sessionGapMs 会话间隔（毫秒）
     * @return 每个事件对应的会话窗口信息
     */
    public static List<WindowInfo> getSessionWindows(List<Long> eventTimestamps, long sessionGapMs) {
        if (eventTimestamps == null || eventTimestamps.isEmpty() || sessionGapMs <= 0) {
            return new ArrayList<>();
        }
        List<WindowInfo> result = new ArrayList<>();
        long sessionStart = eventTimestamps.get(0);
        long sessionEnd = sessionStart;
        int sessionIndex = 0;
        for (int i = 0; i < eventTimestamps.size(); i++) {
            Long ts = eventTimestamps.get(i);
            if (i > 0 && (ts - sessionEnd) > sessionGapMs) {
                for (int j = result.size(); j < i; j++) {
                    result.add(WindowInfo.builder()
                            .windowStart(sessionStart)
                            .windowEnd(sessionEnd)
                            .windowKey("session_" + sessionGapMs + "_" + sessionStart)
                            .windowIndex(sessionIndex)
                            .build());
                }
                sessionStart = ts;
                sessionIndex++;
            }
            sessionEnd = ts;
        }
        while (result.size() < eventTimestamps.size()) {
            result.add(WindowInfo.builder()
                    .windowStart(sessionStart)
                    .windowEnd(sessionEnd)
                    .windowKey("session_" + sessionGapMs + "_" + sessionStart)
                    .windowIndex(sessionIndex)
                    .build());
        }
        return result;
    }

    /**
     * 对齐到自然时间窗口（按分钟对齐）
     *
     * @param timestamp 时间戳
     * @param minutes 分钟数（如5表示5分钟窗口）
     * @return 窗口信息
     */
    public static WindowInfo alignToMinuteWindow(Long timestamp, int minutes) {
        if (timestamp == null || minutes <= 0) {
            throw new IllegalArgumentException("参数无效");
        }
        Instant instant = Instant.ofEpochMilli(timestamp);
        LocalDateTime dateTime = LocalDateTime.ofInstant(instant, ZoneId.systemDefault());
        int minuteOfHour = dateTime.getMinute();
        int alignedMinute = (minuteOfHour / minutes) * minutes;
        LocalDateTime startDateTime = dateTime.withMinute(alignedMinute).withSecond(0).withNano(0);
        long start = startDateTime.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        long end = start + (long) minutes * ONE_MINUTE;
        return WindowInfo.builder()
                .windowStart(start)
                .windowEnd(end)
                .windowKey("minute_aligned_" + minutes + "_" + start)
                .windowIndex(alignedMinute / minutes)
                .build();
    }

    /**
     * 对齐到自然小时窗口
     *
     * @param timestamp 时间戳
     * @param hours 小时数
     * @return 窗口信息
     */
    public static WindowInfo alignToHourWindow(Long timestamp, int hours) {
        if (timestamp == null || hours <= 0) {
            throw new IllegalArgumentException("参数无效");
        }
        Instant instant = Instant.ofEpochMilli(timestamp);
        LocalDateTime dateTime = LocalDateTime.ofInstant(instant, ZoneId.systemDefault());
        int hourOfDay = dateTime.getHour();
        int alignedHour = (hourOfDay / hours) * hours;
        LocalDateTime startDateTime = dateTime.withHour(alignedHour).withMinute(0).withSecond(0).withNano(0);
        long start = startDateTime.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        long end = start + (long) hours * ONE_HOUR;
        return WindowInfo.builder()
                .windowStart(start)
                .windowEnd(end)
                .windowKey("hour_aligned_" + hours + "_" + start)
                .windowIndex(alignedHour / hours)
                .build();
    }

    /**
     * 对齐到自然天窗口
     *
     * @param timestamp 时间戳
     * @return 窗口信息
     */
    public static WindowInfo alignToDayWindow(Long timestamp) {
        if (timestamp == null) {
            throw new IllegalArgumentException("参数无效");
        }
        Instant instant = Instant.ofEpochMilli(timestamp);
        LocalDateTime dateTime = LocalDateTime.ofInstant(instant, ZoneId.systemDefault());
        LocalDateTime startDateTime = dateTime.withHour(0).withMinute(0).withSecond(0).withNano(0);
        long start = startDateTime.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        long end = start + ONE_DAY;
        return WindowInfo.builder()
                .windowStart(start)
                .windowEnd(end)
                .windowKey("day_aligned_" + start)
                .windowIndex(startDateTime.getDayOfMonth())
                .build();
    }

    /**
     * 格式化时长为可读字符串
     *
     * @param millis 毫秒数
     * @return 可读时长（如 "2h30m15s"）
     */
    public static String formatDuration(Long millis) {
        if (millis == null || millis < 0) {
            return "0s";
        }
        Duration duration = Duration.ofMillis(millis);
        long days = duration.toDays();
        long hours = duration.toHours() % 24;
        long minutes = duration.toMinutes() % 60;
        long seconds = duration.getSeconds() % 60;
        StringBuilder sb = new StringBuilder();
        if (days > 0) {
            sb.append(days).append("d");
        }
        if (hours > 0) {
            sb.append(hours).append("h");
        }
        if (minutes > 0) {
            sb.append(minutes).append("m");
        }
        if (seconds > 0 || sb.length() == 0) {
            sb.append(seconds).append("s");
        }
        return sb.toString();
    }

    /**
     * 解析时间字符串为毫秒
     *
     * @param durationStr 时长字符串（如 "5m", "2h30m", "1d12h"）
     * @return 毫秒数
     */
    public static Long parseDuration(String durationStr) {
        if (durationStr == null || durationStr.isEmpty()) {
            return null;
        }
        long totalMillis = 0;
        String numStr = "";
        for (int i = 0; i < durationStr.length(); i++) {
            char c = durationStr.charAt(i);
            if (Character.isDigit(c)) {
                numStr += c;
            } else {
                if (numStr.isEmpty()) {
                    continue;
                }
                long value = Long.parseLong(numStr);
                switch (c) {
                    case 'd':
                        totalMillis += value * ONE_DAY;
                        break;
                    case 'h':
                        totalMillis += value * ONE_HOUR;
                        break;
                    case 'm':
                        totalMillis += value * ONE_MINUTE;
                        break;
                    case 's':
                        totalMillis += value * ONE_SECOND;
                        break;
                    default:
                        log.warn("未知的时间单位: {}", c);
                }
                numStr = "";
            }
        }
        return totalMillis > 0 ? totalMillis : null;
    }

    /**
     * 获取ChronoUnit对应的毫秒数
     *
     * @param amount 数量
     * @param unit 时间单位
     * @return 毫秒数
     */
    public static long toMillis(long amount, ChronoUnit unit) {
        return unit.getDuration().multipliedBy(amount).toMillis();
    }
}
