package com.cicd.common.util;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class VariableSubstitutor {

    private static final Pattern PATTERN = Pattern.compile("\\$\\{([^}]+)}");

    public static String substitute(String input, Map<String, String> variables) {
        if (input == null || variables == null || variables.isEmpty()) {
            return input;
        }
        Matcher matcher = PATTERN.matcher(input);
        StringBuffer sb = new StringBuffer();
        while (matcher.find()) {
            String key = matcher.group(1);
            String value = variables.getOrDefault(key, matcher.group(0));
            matcher.appendReplacement(sb, Matcher.quoteReplacement(value));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    public static Map<String, String> substituteMap(Map<String, String> input, Map<String, String> variables) {
        if (input == null || variables == null || variables.isEmpty()) {
            return input;
        }
        input.replaceAll((k, v) -> substitute(v, variables));
        return input;
    }
}
