package com.observability.alert.parser;

import cn.hutool.core.util.StrUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Component
public class AlertExpressionParser {

    private static final Pattern COMPARISON_PATTERN =
            Pattern.compile("^\\s*(\\w+)\\s*(>=|<=|>|<|==|!=)\\s*([\\d.]+)\\s*$");

    private static final Pattern LOGICAL_PATTERN =
            Pattern.compile("\\s*(&&|\\|\\|)\\s*");

    private final Map<String, ParsedExpression> expressionCache = new ConcurrentHashMap<>();

    public boolean evaluate(String expression, Map<String, Double> metrics) {
        if (StrUtil.isBlank(expression)) {
            return false;
        }

        ParsedExpression parsed = expressionCache.computeIfAbsent(expression, this::parse);
        return parsed.evaluate(metrics);
    }

    private ParsedExpression parse(String expression) {
        String[] parts = LOGICAL_PATTERN.split(expression);
        String[] operators = extractOperators(expression);

        return new ParsedExpression(parts, operators);
    }

    private String[] extractOperators(String expression) {
        Matcher matcher = LOGICAL_PATTERN.matcher(expression);
        java.util.List<String> operators = new java.util.ArrayList<>();
        while (matcher.find()) {
            operators.add(matcher.group(1));
        }
        return operators.toArray(new String[0]);
    }

    private record ParsedExpression(String[] conditions, String[] operators) {

        boolean evaluate(Map<String, Double> metrics) {
            boolean result = evaluateCondition(conditions[0], metrics);

            for (int i = 0; i < operators.length; i++) {
                boolean nextResult = evaluateCondition(conditions[i + 1], metrics);
                result = switch (operators[i]) {
                    case "&&" -> result && nextResult;
                    case "||" -> result || nextResult;
                    default -> result;
                };
            }

            return result;
        }

        private boolean evaluateCondition(String condition, Map<String, Double> metrics) {
            Matcher matcher = COMPARISON_PATTERN.matcher(condition.trim());
            if (!matcher.matches()) {
                log.warn("Invalid condition: {}", condition);
                return false;
            }

            String metricName = matcher.group(1);
            String operator = matcher.group(2);
            double threshold = Double.parseDouble(matcher.group(3));

            Double value = metrics.get(metricName);
            if (value == null) {
                return false;
            }

            return switch (operator) {
                case ">" -> value > threshold;
                case ">=" -> value >= threshold;
                case "<" -> value < threshold;
                case "<=" -> value <= threshold;
                case "==" -> Math.abs(value - threshold) < 0.0001;
                case "!=" -> Math.abs(value - threshold) >= 0.0001;
                default -> false;
            };
        }
    }

    public void clearCache() {
        expressionCache.clear();
    }
}
