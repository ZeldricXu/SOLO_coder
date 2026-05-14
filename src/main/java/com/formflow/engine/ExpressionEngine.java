package com.formflow.engine;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class ExpressionEngine {

    private static final Logger logger = LoggerFactory.getLogger(ExpressionEngine.class);

    private static final Pattern VARIABLE_PATTERN = Pattern.compile("\\$\\{([^}]+)\\}");
    private static final Pattern FUNCTION_PATTERN = Pattern.compile("(\\w+)\\(([^)]*)\\)");

    private final Map<String, ExpressionFunction> functions = new HashMap<>();

    public ExpressionEngine() {
        registerBuiltinFunctions();
    }

    private void registerBuiltinFunctions() {
        functions.put("today", new TodayFunction());
        functions.put("now", new NowFunction());
        functions.put("contains", new ContainsFunction());
        functions.put("startsWith", new StartsWithFunction());
        functions.put("endsWith", new EndsWithFunction());
        functions.put("length", new LengthFunction());
        functions.put("isEmpty", new IsEmptyFunction());
        functions.put("isNotEmpty", new IsNotEmptyFunction());
        functions.put("toUpperCase", new ToUpperCaseFunction());
        functions.put("toLowerCase", new ToLowerCaseFunction());
        functions.put("in", new InFunction());
        functions.put("between", new BetweenFunction());
        functions.put("age", new AgeFunction());
        functions.put("daysBetween", new DaysBetweenFunction());
        functions.put("monthsBetween", new MonthsBetweenFunction());
    }

    public boolean evaluate(String expression, Map<String, Object> variables) {
        if (expression == null || expression.trim().isEmpty()) {
            return true;
        }

        try {
            String processedExpr = substituteVariables(expression, variables);

            Boolean result = evaluateBooleanExpression(processedExpr, variables);

            if (result != null) {
                return result;
            }

            Object objResult = evaluateExpression(processedExpr, variables);
            return toBoolean(objResult);

        } catch (Exception e) {
            logger.warn("表达式执行失败: {}, error: {}", expression, e.getMessage());
            return false;
        }
    }

    public Object evaluateValue(String expression, Map<String, Object> variables) {
        if (expression == null || expression.trim().isEmpty()) {
            return null;
        }

        try {
            String processedExpr = substituteVariables(expression, variables);
            return evaluateExpression(processedExpr, variables);
        } catch (Exception e) {
            logger.warn("表达式求值失败: {}, error: {}", expression, e.getMessage());
            return null;
        }
    }

    private String substituteVariables(String expression, Map<String, Object> variables) {
        String result = expression;
        Matcher matcher = VARIABLE_PATTERN.matcher(expression);

        while (matcher.find()) {
            String varName = matcher.group(1).trim();
            Object value = getVariableValue(varName, variables);
            String replacement = valueToString(value);
            result = result.replace("${" + varName + "}", replacement);
        }

        return result;
    }

    private Object getVariableValue(String varName, Map<String, Object> variables) {
        if (variables == null) {
            return null;
        }

        if (varName.contains(".")) {
            String[] parts = varName.split("\\.");
            Object current = variables;
            for (String part : parts) {
                if (current instanceof Map) {
                    current = ((Map<?, ?>) current).get(part);
                } else {
                    return null;
                }
            }
            return current;
        }

        return variables.get(varName);
    }

    private String valueToString(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof String) {
            return "'" + escapeString((String) value) + "'";
        }
        return value.toString();
    }

    private String escapeString(String s) {
        return s.replace("'", "\\'").replace("\"", "\\\"");
    }

    private Boolean evaluateBooleanExpression(String expr, Map<String, Object> variables) {
        String trimmed = expr.trim();

        if (trimmed.equalsIgnoreCase("true")) {
            return true;
        }
        if (trimmed.equalsIgnoreCase("false")) {
            return false;
        }

        int depth = 0;
        for (int i = 0; i < trimmed.length(); i++) {
            char c = trimmed.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
            } else if (depth == 0) {
                if (i >= 2 && trimmed.substring(i - 2, i + 1).equalsIgnoreCase("AND")) {
                    String left = trimmed.substring(0, i - 2).trim();
                    String right = trimmed.substring(i + 1).trim();
                    return toBoolean(evaluateExpression(left, variables))
                            && toBoolean(evaluateExpression(right, variables));
                }
                if (i >= 1 && trimmed.substring(i - 1, i + 2).equalsIgnoreCase("OR ")) {
                    String left = trimmed.substring(0, i - 1).trim();
                    String right = trimmed.substring(i + 2).trim();
                    return toBoolean(evaluateExpression(left, variables))
                            || toBoolean(evaluateExpression(right, variables));
                }
            }
        }

        if (trimmed.startsWith("(") && trimmed.endsWith(")")) {
            return evaluateBooleanExpression(trimmed.substring(1, trimmed.length() - 1), variables);
        }

        if (trimmed.toLowerCase().startsWith("not ")) {
            Boolean result = evaluateBooleanExpression(trimmed.substring(4), variables);
            return result != null && !result;
        }
        if (trimmed.toLowerCase().startsWith("!")) {
            Boolean result = evaluateBooleanExpression(trimmed.substring(1), variables);
            return result != null && !result;
        }

        return null;
    }

    private Object evaluateExpression(String expr, Map<String, Object> variables) {
        String trimmed = expr.trim();

        if (trimmed.startsWith("(") && trimmed.endsWith(")")) {
            int depth = 1;
            for (int i = 1; i < trimmed.length() - 1; i++) {
                if (trimmed.charAt(i) == '(') depth++;
                if (trimmed.charAt(i) == ')') depth--;
                if (depth == 0) {
                    break;
                }
            }
            if (depth == 0) {
                return evaluateExpression(trimmed.substring(1, trimmed.length() - 1), variables);
            }
        }

        Matcher funcMatcher = FUNCTION_PATTERN.matcher(trimmed);
        if (funcMatcher.matches()) {
            String funcName = funcMatcher.group(1);
            String argsStr = funcMatcher.group(2);
            return executeFunction(funcName, argsStr, variables);
        }

        int cmpIndex = findComparisonOperatorIndex(trimmed);
        if (cmpIndex >= 0) {
            String[] parts = splitAtComparison(trimmed, cmpIndex);
            if (parts != null) {
                Object left = evaluateExpression(parts[0], variables);
                Object right = evaluateExpression(parts[2], variables);
                String op = parts[1];
                return compareValues(left, right, op);
            }
        }

        if (trimmed.startsWith("'") && trimmed.endsWith("'")) {
            return trimmed.substring(1, trimmed.length() - 1).replace("\\'", "'");
        }
        if (trimmed.startsWith("\"") && trimmed.endsWith("\"")) {
            return trimmed.substring(1, trimmed.length() - 1).replace("\\\"", "\"");
        }

        if (trimmed.equalsIgnoreCase("null")) {
            return null;
        }
        if (trimmed.equalsIgnoreCase("true")) {
            return true;
        }
        if (trimmed.equalsIgnoreCase("false")) {
            return false;
        }

        try {
            if (trimmed.contains(".")) {
                return new BigDecimal(trimmed);
            }
            return Long.parseLong(trimmed);
        } catch (NumberFormatException e) {
            return variables != null ? variables.get(trimmed) : trimmed;
        }
    }

    private int findComparisonOperatorIndex(String expr) {
        int depth = 0;
        for (int i = 0; i < expr.length(); i++) {
            char c = expr.charAt(i);
            if (c == '(') depth++;
            else if (c == ')') depth--;
            else if (depth == 0) {
                if (i < expr.length() - 1) {
                    String twoChar = expr.substring(i, i + 2);
                    if (twoChar.equals(">=") || twoChar.equals("<=")
                            || twoChar.equals("==") || twoChar.equals("!=")) {
                        return i;
                    }
                }
                if (c == '>' || c == '<') {
                    if (i == expr.length() - 1 || (expr.charAt(i + 1) != '=')) {
                        return i;
                    }
                }
            }
        }
        return -1;
    }

    private String[] splitAtComparison(String expr, int index) {
        String op;
        int endIndex;

        if (index < expr.length() - 1) {
            String twoChar = expr.substring(index, index + 2);
            if (twoChar.equals(">=") || twoChar.equals("<=")
                    || twoChar.equals("==") || twoChar.equals("!=")) {
                op = twoChar;
                endIndex = index + 2;
            } else {
                op = String.valueOf(expr.charAt(index));
                endIndex = index + 1;
            }
        } else {
            op = String.valueOf(expr.charAt(index));
            endIndex = index + 1;
        }

        String left = expr.substring(0, index).trim();
        String right = expr.substring(endIndex).trim();

        if (left.isEmpty() || right.isEmpty()) {
            return null;
        }

        return new String[]{left, op, right};
    }

    private Boolean compareValues(Object left, Object right, String op) {
        if (left == null || right == null) {
            boolean bothNull = left == null && right == null;
            switch (op) {
                case "==": return bothNull;
                case "!=": return !bothNull;
                default: return false;
            }
        }

        try {
            BigDecimal leftNum = toBigDecimal(left);
            BigDecimal rightNum = toBigDecimal(right);
            if (leftNum != null && rightNum != null) {
                int cmp = leftNum.compareTo(rightNum);
                return compareNumericResult(cmp, op);
            }
        } catch (Exception e) {
            logger.debug("数值比较失败，尝试字符串比较: {}", e.getMessage());
        }

        String leftStr = left.toString();
        String rightStr = right.toString();

        switch (op) {
            case "==":
            case "=":
                return leftStr.equals(rightStr);
            case "!=":
                return !leftStr.equals(rightStr);
            case ">":
                return leftStr.compareTo(rightStr) > 0;
            case ">=":
                return leftStr.compareTo(rightStr) >= 0;
            case "<":
                return leftStr.compareTo(rightStr) < 0;
            case "<=":
                return leftStr.compareTo(rightStr) <= 0;
            default:
                return false;
        }
    }

    private Boolean compareNumericResult(int cmp, String op) {
        switch (op) {
            case "==":
            case "=":
                return cmp == 0;
            case "!=":
                return cmp != 0;
            case ">":
                return cmp > 0;
            case ">=":
                return cmp >= 0;
            case "<":
                return cmp < 0;
            case "<=":
                return cmp <= 0;
            default:
                return false;
        }
    }

    private BigDecimal toBigDecimal(Object obj) {
        if (obj == null) return null;
        if (obj instanceof BigDecimal) return (BigDecimal) obj;
        if (obj instanceof Number) return new BigDecimal(obj.toString());
        try {
            return new BigDecimal(obj.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Object executeFunction(String funcName, String argsStr, Map<String, Object> variables) {
        ExpressionFunction function = functions.get(funcName);
        if (function == null) {
            logger.warn("未找到函数: {}", funcName);
            return null;
        }

        List<Object> args = parseFunctionArgs(argsStr, variables);
        return function.execute(args, variables);
    }

    private List<Object> parseFunctionArgs(String argsStr, Map<String, Object> variables) {
        List<Object> args = new ArrayList<>();
        if (argsStr == null || argsStr.trim().isEmpty()) {
            return args;
        }

        int depth = 0;
        int stringStart = -1;
        boolean inString = false;
        int argStart = 0;

        for (int i = 0; i < argsStr.length(); i++) {
            char c = argsStr.charAt(i);

            if ((c == '\'' || c == '"') && (i == 0 || argsStr.charAt(i - 1) != '\\')) {
                if (!inString) {
                    inString = true;
                    stringStart = i;
                } else if ((c == '\'' && argsStr.charAt(stringStart) == '\'')
                        || (c == '"' && argsStr.charAt(stringStart) == '"')) {
                    inString = false;
                }
            } else if (!inString) {
                if (c == '(' || c == '[') depth++;
                else if (c == ')' || c == ']') depth--;
                else if (c == ',' && depth == 0) {
                    String argExpr = argsStr.substring(argStart, i).trim();
                    args.add(evaluateExpression(argExpr, variables));
                    argStart = i + 1;
                }
            }
        }

        String lastArg = argsStr.substring(argStart).trim();
        if (!lastArg.isEmpty()) {
            args.add(evaluateExpression(lastArg, variables));
        }

        return args;
    }

    private boolean toBoolean(Object obj) {
        if (obj == null) return false;
        if (obj instanceof Boolean) return (Boolean) obj;
        if (obj instanceof Number) return ((Number) obj).doubleValue() != 0;
        String s = obj.toString();
        return !s.isEmpty() && !s.equalsIgnoreCase("false") && !s.equals("0");
    }

    public void registerFunction(String name, ExpressionFunction function) {
        functions.put(name, function);
    }

    public interface ExpressionFunction {
        Object execute(List<Object> args, Map<String, Object> variables);
    }

    static class TodayFunction implements ExpressionFunction {
        @Override
        public Object execute(List<Object> args, Map<String, Object> variables) {
            return LocalDate.now();
        }
    }

    static class NowFunction implements ExpressionFunction {
        @Override
        public Object execute(List<Object> args, Map<String, Object> variables) {
            return LocalDateTime.now();
        }
    }

    static class ContainsFunction implements ExpressionFunction {
        @Override
        public Object execute(List<Object> args, Map<String, Object> variables) {
            if (args.size() < 2) return false;
            String str = args.get(0) != null ? args.get(0).toString() : "";
            String substr = args.get(1) != null ? args.get(1).toString() : "";
            return str.contains(substr);
        }
    }

    static class StartsWithFunction implements ExpressionFunction {
        @Override
        public Object execute(List<Object> args, Map<String, Object> variables) {
            if (args.size() < 2) return false;
            String str = args.get(0) != null ? args.get(0).toString() : "";
            String prefix = args.get(1) != null ? args.get(1).toString() : "";
            return str.startsWith(prefix);
        }
    }

    static class EndsWithFunction implements ExpressionFunction {
        @Override
        public Object execute(List<Object> args, Map<String, Object> variables) {
            if (args.size() < 2) return false;
            String str = args.get(0) != null ? args.get(0).toString() : "";
            String suffix = args.get(1) != null ? args.get(1).toString() : "";
            return str.endsWith(suffix);
        }
    }

    static class LengthFunction implements ExpressionFunction {
        @Override
        public Object execute(List<Object> args, Map<String, Object> variables) {
            if (args.isEmpty()) return 0;
            String str = args.get(0) != null ? args.get(0).toString() : "";
            return str.length();
        }
    }

    static class IsEmptyFunction implements ExpressionFunction {
        @Override
        public Object execute(List<Object> args, Map<String, Object> variables) {
            if (args.isEmpty()) return true;
            Object obj = args.get(0);
            if (obj == null) return true;
            if (obj instanceof String) return ((String) obj).isEmpty();
            if (obj instanceof Collection) return ((Collection<?>) obj).isEmpty();
            if (obj.getClass().isArray()) return ((Object[]) obj).length == 0;
            return obj.toString().isEmpty();
        }
    }

    static class IsNotEmptyFunction implements ExpressionFunction {
        @Override
        public Object execute(List<Object> args, Map<String, Object> variables) {
            Boolean empty = (Boolean) new IsEmptyFunction().execute(args, variables);
            return !empty;
        }
    }

    static class ToUpperCaseFunction implements ExpressionFunction {
        @Override
        public Object execute(List<Object> args, Map<String, Object> variables) {
            if (args.isEmpty()) return "";
            String str = args.get(0) != null ? args.get(0).toString() : "";
            return str.toUpperCase();
        }
    }

    static class ToLowerCaseFunction implements ExpressionFunction {
        @Override
        public Object execute(List<Object> args, Map<String, Object> variables) {
            if (args.isEmpty()) return "";
            String str = args.get(0) != null ? args.get(0).toString() : "";
            return str.toLowerCase();
        }
    }

    static class InFunction implements ExpressionFunction {
        @Override
        public Object execute(List<Object> args, Map<String, Object> variables) {
            if (args.size() < 2) return false;
            Object value = args.get(0);
            for (int i = 1; i < args.size(); i++) {
                if (Objects.equals(value, args.get(i))) {
                    return true;
                }
            }
            return false;
        }
    }

    static class BetweenFunction implements ExpressionFunction {
        @Override
        public Object execute(List<Object> args, Map<String, Object> variables) {
            if (args.size() < 3) return false;
            try {
                BigDecimal value = toBigDecimal(args.get(0));
                BigDecimal min = toBigDecimal(args.get(1));
                BigDecimal max = toBigDecimal(args.get(2));
                if (value == null || min == null || max == null) return false;
                return value.compareTo(min) >= 0 && value.compareTo(max) <= 0;
            } catch (Exception e) {
                return false;
            }
        }
    }

    static class AgeFunction implements ExpressionFunction {
        @Override
        public Object execute(List<Object> args, Map<String, Object> variables) {
            if (args.isEmpty()) return 0;
            try {
                LocalDate birthDate = parseLocalDate(args.get(0));
                if (birthDate == null) return 0;
                return java.time.Period.between(birthDate, LocalDate.now()).getYears();
            } catch (Exception e) {
                return 0;
            }
        }
    }

    static class DaysBetweenFunction implements ExpressionFunction {
        @Override
        public Object execute(List<Object> args, Map<String, Object> variables) {
            if (args.size() < 2) return 0;
            try {
                LocalDate date1 = parseLocalDate(args.get(0));
                LocalDate date2 = parseLocalDate(args.get(1));
                if (date1 == null || date2 == null) return 0;
                return Math.abs(java.time.temporal.ChronoUnit.DAYS.between(date1, date2));
            } catch (Exception e) {
                return 0;
            }
        }
    }

    static class MonthsBetweenFunction implements ExpressionFunction {
        @Override
        public Object execute(List<Object> args, Map<String, Object> variables) {
            if (args.size() < 2) return 0;
            try {
                LocalDate date1 = parseLocalDate(args.get(0));
                LocalDate date2 = parseLocalDate(args.get(1));
                if (date1 == null || date2 == null) return 0;
                return Math.abs(java.time.temporal.ChronoUnit.MONTHS.between(date1, date2));
            } catch (Exception e) {
                return 0;
            }
        }
    }

    private static LocalDate parseLocalDate(Object obj) {
        if (obj == null) return null;
        if (obj instanceof LocalDate) return (LocalDate) obj;
        if (obj instanceof LocalDateTime) return ((LocalDateTime) obj).toLocalDate();
        try {
            return LocalDate.parse(obj.toString());
        } catch (Exception e) {
            try {
                DateTimeFormatter[] formatters = {
                        DateTimeFormatter.ofPattern("yyyy-MM-dd"),
                        DateTimeFormatter.ofPattern("yyyy/MM/dd"),
                        DateTimeFormatter.ofPattern("yyyyMMdd")
                };
                for (DateTimeFormatter formatter : formatters) {
                    try {
                        return LocalDate.parse(obj.toString(), formatter);
                    } catch (Exception ex) {
                        continue;
                    }
                }
            } catch (Exception ex) {
                return null;
            }
            return null;
        }
    }
}
