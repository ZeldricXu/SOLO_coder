package com.exam.util;

import lombok.extern.slf4j.Slf4j;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
public class TextSanitizer {

    private static final Pattern EMOJI_PATTERN = Pattern.compile(
            "[\\x{1F300}-\\x{1F9FF}" +
            "\\x{2600}-\\x{27BF}" +
            "\\x{1F680}-\\x{1F6FF}" +
            "\\x{1F100}-\\x{1F1FF}" +
            "\\x{2300}-\\x{23FF}" +
            "\\x{2B00}-\\x{2BFF}" +
            "\\x{1F700}-\\x{1F77F}" +
            "\\x{1F780}-\\x{1F7FF}" +
            "\\x{1F800}-\\x{1F8FF}" +
            "\\x{1F900}-\\x{1F9FF}" +
            "\\x{1FA00}-\\x{1FA6F}" +
            "\\x{1FA70}-\\x{1FAFF}" +
            "\\x{1F201}-\\x{1F202}" +
            "\\x{1F21A}-\\x{1F22F}" +
            "\\x{1F230}-\\x{1F23B}" +
            "\\x{00A9}-\\x{00AE}" +
            "\\x{200D}" +
            "\\x{FE0E}-\\x{FE0F}" +
            "]",
            Pattern.UNICODE_CASE);

    private static final Pattern ALLOWED_EXAM_TEXT = Pattern.compile(
            "^[\\u4E00-\\u9FFF\\u3400-\\u4DBF\\u20000-\\u2A6DF" +
            "\\uF900-\\uFAFF\\u2F800-\\u2FA1F" +
            "\\u3000-\\u303F\\uFF00-\\uFFEF" +
            "\\u0000-\\u007F" +
            "\\u0080-\\u00FF" +
            "\\u0100-\\u017F" +
            "\\u0180-\\u024F" +
            "\\s\\r\\n\\t" +
            "]*$"
    );

    private TextSanitizer() {
    }

    public static String sanitizeExamField(String text) {
        if (text == null) {
            return "";
        }
        String cleaned = stripEmoji(text);
        cleaned = stripNonCJKOrASCII(cleaned);
        return cleaned;
    }

    public static String sanitizeForPDF(String text) {
        if (text == null) {
            return "";
        }
        String sanitized = sanitizeExamField(text);
        return escapeUnicodeForPDF(sanitized);
    }

    public static String stripEmoji(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < text.length(); ) {
            int codePoint = text.codePointAt(i);
            if (!isEmojiOrSymbol(codePoint)) {
                sb.appendCodePoint(codePoint);
            } else {
                sb.append("\\u").append(String.format("%04X", codePoint));
            }
            i += Character.charCount(codePoint);
        }
        return sb.toString();
    }

    public static String stripNonCJKOrASCII(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < text.length(); ) {
            int codePoint = text.codePointAt(i);
            if (isAllowed(codePoint)) {
                sb.appendCodePoint(codePoint);
            } else {
                sb.append("\\u").append(String.format("%04X", codePoint));
                log.debug("替换非法字符 U+{} -> \\u{}",
                        String.format("%04X", codePoint), String.format("%04X", codePoint));
            }
            i += Character.charCount(codePoint);
        }
        return sb.toString();
    }

    public static String escapeUnicodeForPDF(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c <= 0x7F && c >= 0x20) {
                sb.append(c);
            } else if (c == '\n' || c == '\r' || c == '\t') {
                sb.append(c);
            } else if (c < 0x100) {
                sb.append(String.format("\\u%04X", (int) c));
            } else {
                sb.append(String.format("\\u%04X", (int) c));
            }
        }
        return sb.toString();
    }

    public static boolean containsEmoji(String text) {
        if (text == null || text.isEmpty()) return false;
        for (int i = 0; i < text.length(); ) {
            int codePoint = text.codePointAt(i);
            if (isEmojiOrSymbol(codePoint)) {
                return true;
            }
            i += Character.charCount(codePoint);
        }
        return false;
    }

    public static boolean hasIllegalExamCharacters(String text) {
        if (text == null || text.isEmpty()) return false;
        for (int i = 0; i < text.length(); ) {
            int codePoint = text.codePointAt(i);
            if (!isAllowed(codePoint)) {
                return true;
            }
            i += Character.charCount(codePoint);
        }
        return false;
    }

    private static boolean isAllowed(int cp) {
        if (cp >= 0x0000 && cp <= 0x007F) return true;
        if (cp >= 0x0080 && cp <= 0x024F) return true;
        if (cp >= 0x0250 && cp <= 0x02AF) return true;
        if (cp >= 0x3000 && cp <= 0x303F) return true;
        if (cp >= 0x3400 && cp <= 0x4DBF) return true;
        if (cp >= 0x4E00 && cp <= 0x9FFF) return true;
        if (cp >= 0xF900 && cp <= 0xFAFF) return true;
        if (cp >= 0xFF00 && cp <= 0xFFEF) return true;
        if (cp >= 0x20000 && cp <= 0x2A6DF) return true;
        if (cp >= 0x2A700 && cp <= 0x2B73F) return true;
        if (cp >= 0x2F800 && cp <= 0x2FA1F) return true;
        if (cp == '\n' || cp == '\r' || cp == '\t') return true;
        return false;
    }

    private static boolean isEmojiOrSymbol(int cp) {
        if (cp == 0x200D || cp == 0xFE0E || cp == 0xFE0F) return true;
        if (cp >= 0x1F300 && cp <= 0x1FAFF) return true;
        if (cp >= 0x2600 && cp <= 0x27BF) return true;
        if (cp >= 0x2300 && cp <= 0x23FF) return true;
        if (cp >= 0x2B00 && cp <= 0x2BFF) return true;
        if (cp >= 0x1F100 && cp <= 0x1F2FF) return true;
        if (cp >= 0x00A9 && cp <= 0x00AE) return true;
        return false;
    }
}
