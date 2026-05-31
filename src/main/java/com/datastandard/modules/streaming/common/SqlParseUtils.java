package com.datastandard.modules.streaming.common;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class SqlParseUtils {

    private SqlParseUtils() {}

    public static String normalizeSql(String sql) {
        return sql.trim().replaceAll("\\s+", " ");
    }

    public static int findEndIndex(String sql, int... indices) {
        List<Integer> validIndices = new ArrayList<>();
        for (int idx : indices) {
            if (idx != -1) {
                validIndices.add(idx);
            }
        }
        if (validIndices.isEmpty()) {
            return -1;
        }
        Collections.sort(validIndices);
        return validIndices.get(0);
    }

    public static String extractClause(String sql, String keyword, int fromIndex, int endIndex) {
        int keywordIdx = sql.toUpperCase().indexOf(keyword, fromIndex);
        if (keywordIdx == -1) {
            return "";
        }
        int actualEnd = endIndex != -1 ? endIndex : sql.length();
        return sql.substring(keywordIdx + keyword.length(), actualEnd).trim();
    }

    public static boolean isParenthesesBalanced(String sql) {
        int openParens = 0;
        int closeParens = 0;
        for (char c : sql.toCharArray()) {
            if (c == '(') openParens++;
            if (c == ')') closeParens++;
        }
        return openParens == closeParens;
    }

    public static String extractFirstToken(String input) {
        if (input == null || input.isEmpty()) {
            return input;
        }
        return input.split(" ")[0];
    }

    public static String[] splitByComma(String str) {
        if (str == null || str.isEmpty()) {
            return new String[0];
        }
        String[] parts = str.split(",");
        for (int i = 0; i < parts.length; i++) {
            parts[i] = parts[i].trim();
        }
        return parts;
    }
}
