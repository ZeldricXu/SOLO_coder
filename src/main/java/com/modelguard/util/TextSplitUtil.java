package com.modelguard.util;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

public final class TextSplitUtil {

    private static final List<String> SENTENCE_SEPARATORS = Arrays.asList(
            "[。！？.!?]",
            "[，,;；]",
            "[\n\r]",
            "[ 　]"
    );

    private static final Pattern VARIABLE_PATTERN = Pattern.compile("\\{\\{([^}]+)}}");

    private TextSplitUtil() {
    }

    public static List<String> smartSplit(String content, int chunkSize, int chunkOverlap) {
        return smartSplit(content, chunkSize, chunkOverlap, null);
    }

    public static List<String> smartSplit(String content, int chunkSize, int chunkOverlap, String separator) {
        if (content == null || content.isEmpty()) {
            return new ArrayList<>();
        }

        if (separator != null && !separator.isEmpty()) {
            return splitWithOverlap(content, separator, chunkSize, chunkOverlap);
        }

        String remaining = content;
        List<String> chunks = new ArrayList<>();
        String lastChunk = "";

        while (!remaining.isEmpty()) {
            String chunk;
            if (remaining.length() <= chunkSize) {
                chunk = remaining;
                remaining = "";
            } else {
                int splitPoint = findSplitPoint(remaining, chunkSize);
                chunk = remaining.substring(0, splitPoint).trim();
                remaining = remaining.substring(splitPoint).trim();
            }

            if (!lastChunk.isEmpty() && chunkOverlap > 0) {
                String overlap = getOverlap(lastChunk, chunkOverlap);
                if (!chunk.startsWith(overlap)) {
                    chunk = overlap + chunk;
                }
            }

            if (!chunk.isEmpty()) {
                chunks.add(chunk);
            }
            lastChunk = chunk;
        }

        return chunks;
    }

    private static int findSplitPoint(String content, int targetSize) {
        if (content.length() <= targetSize) {
            return content.length();
        }

        String searchRegion = content.substring(Math.max(0, targetSize / 2), targetSize + 50);

        for (String separatorPattern : SENTENCE_SEPARATORS) {
            Pattern pattern = Pattern.compile(separatorPattern);
            java.util.regex.Matcher matcher = pattern.matcher(searchRegion);

            int lastMatch = -1;
            while (matcher.find()) {
                lastMatch = matcher.end();
            }

            if (lastMatch > 0) {
                int splitPoint = Math.max(0, targetSize / 2) + lastMatch;
                if (splitPoint > targetSize * 0.5 && splitPoint <= targetSize * 1.5) {
                    return Math.min(splitPoint, content.length());
                }
            }
        }

        return targetSize;
    }

    private static String getOverlap(String text, int overlapSize) {
        if (text.length() <= overlapSize) {
            return text;
        }
        return text.substring(text.length() - overlapSize);
    }

    private static List<String> splitWithOverlap(String content, String separator, int chunkSize, int chunkOverlap) {
        String[] parts = content.split(Pattern.quote(separator));
        List<String> chunks = new ArrayList<>();
        StringBuilder current = new StringBuilder();

        for (String part : parts) {
            if (current.length() + part.length() + separator.length() <= chunkSize) {
                if (current.length() > 0) {
                    current.append(separator);
                }
                current.append(part);
            } else {
                if (current.length() > 0) {
                    chunks.add(current.toString());
                    String overlap = getOverlap(current.toString(), chunkOverlap);
                    current = new StringBuilder(overlap);
                    if (current.length() > 0) {
                        current.append(separator);
                    }
                    current.append(part);
                } else {
                    chunks.add(part);
                }
            }
        }

        if (current.length() > 0) {
            chunks.add(current.toString());
        }

        return chunks;
    }

    public static String renderTemplate(String template, java.util.Map<String, Object> variables) {
        if (template == null || variables == null || variables.isEmpty()) {
            return template;
        }

        java.util.regex.Matcher matcher = VARIABLE_PATTERN.matcher(template);
        StringBuffer sb = new StringBuffer();

        while (matcher.find()) {
            String varName = matcher.group(1).trim();
            Object value = variables.get(varName);
            String replacement = value != null ? value.toString() : "";
            matcher.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(sb);

        return sb.toString();
    }

    public static List<String> extractVariables(String template) {
        List<String> variables = new ArrayList<>();
        if (template == null) {
            return variables;
        }

        java.util.regex.Matcher matcher = VARIABLE_PATTERN.matcher(template);
        while (matcher.find()) {
            String varName = matcher.group(1).trim();
            if (!variables.contains(varName)) {
                variables.add(varName);
            }
        }
        return variables;
    }
}
