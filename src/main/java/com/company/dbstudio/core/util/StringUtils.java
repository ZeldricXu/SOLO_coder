package com.company.dbstudio.core.util;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

public final class StringUtils {

    private StringUtils() {
    }

    public static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    public static boolean isNotBlank(String s) {
        return !isBlank(s);
    }

    public static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    public static String truncate(String s, int maxLength) {
        if (s == null) {
            return null;
        }
        if (s.length() <= maxLength) {
            return s;
        }
        return s.substring(0, maxLength - 3) + "...";
    }

    public static String join(List<String> parts, String delimiter) {
        if (parts == null || parts.isEmpty()) {
            return "";
        }
        return String.join(delimiter, parts);
    }

    public static List<String> splitToList(String s, String delimiter) {
        if (isBlank(s)) {
            return List.of();
        }
        return Arrays.stream(s.split(delimiter))
                .map(String::trim)
                .filter(StringUtils::isNotBlank)
                .collect(Collectors.toList());
    }

    public static String toCamelCase(String s) {
        if (isBlank(s)) {
            return s;
        }
        StringBuilder result = new StringBuilder();
        boolean nextUpper = false;
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch == '_' || ch == '-') {
                nextUpper = true;
            } else {
                if (nextUpper) {
                    result.append(Character.toUpperCase(ch));
                    nextUpper = false;
                } else {
                    result.append(i == 0 ? Character.toLowerCase(ch) : ch);
                }
            }
        }
        return result.toString();
    }

    public static String toSnakeCase(String s) {
        if (isBlank(s)) {
            return s;
        }
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (Character.isUpperCase(ch)) {
                if (i > 0) {
                    result.append('_');
                }
                result.append(Character.toLowerCase(ch));
            } else {
                result.append(ch);
            }
        }
        return result.toString();
    }

    public static String escapeHtml(String s) {
        if (s == null) {
            return null;
        }
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }

    public static String formatFileSize(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        } else if (bytes < 1024 * 1024) {
            return String.format("%.2f KB", bytes / 1024.0);
        } else if (bytes < 1024 * 1024 * 1024) {
            return String.format("%.2f MB", bytes / (1024.0 * 1024));
        } else {
            return String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024));
        }
    }

    public static String formatDuration(long millis) {
        if (millis < 1000) {
            return millis + " ms";
        } else if (millis < 60 * 1000) {
            return String.format("%.2f s", millis / 1000.0);
        } else if (millis < 60 * 60 * 1000) {
            long minutes = millis / (60 * 1000);
            long seconds = (millis % (60 * 1000)) / 1000;
            return String.format("%d min %d s", minutes, seconds);
        } else {
            long hours = millis / (60 * 60 * 1000);
            long minutes = (millis % (60 * 60 * 1000)) / (60 * 1000);
            return String.format("%d h %d min", hours, minutes);
        }
    }
}
