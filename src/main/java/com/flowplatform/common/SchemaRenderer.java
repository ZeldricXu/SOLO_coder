package com.flowplatform.common;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.flowplatform.common.renderer.RendererRegistry;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

@Slf4j
@Component
public class SchemaRenderer {

    private final RendererRegistry rendererRegistry;

    @Getter
    private final List<String> errors = new ArrayList<>();
    private final Map<String, Object> computedValues = new HashMap<>();

    public SchemaRenderer(RendererRegistry rendererRegistry) {
        this.rendererRegistry = rendererRegistry;
    }

    public String render(String jsonSchema) {
        errors.clear();
        computedValues.clear();
        try {
            JSONObject schema = JSON.parseObject(jsonSchema);
            if (schema == null) {
                errors.add("JSON解析失败：无效的Schema格式");
                return renderErrorHtml();
            }
            JSONArray fields = schema.getJSONArray("fields");
            if (fields == null) {
                errors.add("缺少fields字段定义");
                return renderErrorHtml();
            }
            validateSchema(fields);
            if (!errors.isEmpty()) {
                return renderErrorHtml();
            }
            StringBuilder html = new StringBuilder();
            html.append("<div class=\"form-container\">\n");
            for (int i = 0; i < fields.size(); i++) {
                JSONObject field = fields.getJSONObject(i);
                html.append(rendererRegistry.getRenderer(field.getString("type")).render(field));
            }
            html.append("</div>\n");
            return html.toString();
        } catch (Exception e) {
            errors.add("渲染异常: " + e.getMessage());
            log.error("Schema render error", e);
            return renderErrorHtml();
        }
    }

    private void validateSchema(JSONArray fields) {
        Set<String> fieldKeys = new HashSet<>();
        for (int i = 0; i < fields.size(); i++) {
            JSONObject field = fields.getJSONObject(i);
            String type = field.getString("type");
            String key = field.getString("key");

            if (type == null || type.isEmpty()) {
                errors.add(String.format("字段[%d]缺少type属性", i));
                continue;
            }
            if (key == null || key.isEmpty()) {
                errors.add(String.format("字段[%d]缺少key属性", i));
                continue;
            }
            if (fieldKeys.contains(key)) {
                errors.add(String.format("字段key[%s]重复", key));
                continue;
            }
            fieldKeys.add(key);

            String pattern = field.getString("pattern");
            if (pattern != null && !pattern.isEmpty()) {
                try {
                    Pattern.compile(pattern);
                } catch (PatternSyntaxException e) {
                    errors.add(String.format("字段[%s]正则表达式无效: %s", key, e.getMessage()));
                }
            }

            if ("subTable".equals(type)) {
                JSONArray subFields = field.getJSONArray("subFields");
                if (subFields != null) {
                    validateSubTableFields(subFields, key + ".");
                }
            }
        }

        validateNoCyclicReference(fields);
    }

    private void validateSubTableFields(JSONArray fields, String prefix) {
        Set<String> keys = new HashSet<>();
        for (int i = 0; i < fields.size(); i++) {
            JSONObject field = fields.getJSONObject(i);
            String type = field.getString("type");
            String key = field.getString("key");

            if (type == null) {
                errors.add(String.format("子表%s字段[%d]缺少type属性", prefix, i));
            }
            if (key == null) {
                errors.add(String.format("子表%s字段[%d]缺少key属性", prefix, i));
            } else if (keys.contains(key)) {
                errors.add(String.format("子表%s字段key[%s]重复", prefix, key));
            }
            keys.add(key);
        }
    }

    private void validateNoCyclicReference(JSONArray fields) {
        Map<String, List<String>> formulaDependencies = new HashMap<>();
        for (int i = 0; i < fields.size(); i++) {
            JSONObject field = fields.getJSONObject(i);
            String key = field.getString("key");
            String formula = field.getString("formula");
            if (formula != null && !formula.isEmpty()) {
                List<String> refs = extractFieldReferences(formula);
                formulaDependencies.put(key, refs);
            }
        }

        Set<String> visited = new HashSet<>();
        Set<String> inPath = new HashSet<>();
        for (String key : formulaDependencies.keySet()) {
            if (hasCycle(key, formulaDependencies, visited, inPath)) {
                errors.add(String.format("存在循环引用: 字段[%s]形成依赖环", key));
                break;
            }
        }
    }

    private boolean hasCycle(String field, Map<String, List<String>> deps, Set<String> visited, Set<String> inPath) {
        if (inPath.contains(field)) return true;
        if (visited.contains(field)) return false;
        visited.add(field);
        inPath.add(field);
        List<String> refs = deps.get(field);
        if (refs != null) {
            for (String ref : refs) {
                if (deps.containsKey(ref) && hasCycle(ref, deps, visited, inPath)) {
                    return true;
                }
            }
        }
        inPath.remove(field);
        return false;
    }

    public List<String> extractFieldReferences(String formula) {
        List<String> refs = new ArrayList<>();
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\$\\{([^}]+)\\}").matcher(formula);
        while (m.find()) {
            refs.add(m.group(1));
        }
        return refs;
    }

    private String renderErrorHtml() {
        StringBuilder html = new StringBuilder();
        html.append("<div class=\"render-error\">\n");
        html.append("  <div class=\"error-title\">表单渲染错误</div>\n");
        html.append("  <ul class=\"error-list\">\n");
        for (String error : errors) {
            html.append(String.format("    <li>%s</li>\n", error));
        }
        html.append("  </ul>\n");
        html.append("</div>\n");
        return html.toString();
    }

    public Map<String, Object> calculateFormulas(String jsonSchema, Map<String, Object> values) {
        computedValues.clear();
        try {
            JSONObject schema = JSON.parseObject(jsonSchema);
            JSONArray fields = schema.getJSONArray("fields");
            if (fields == null) return computedValues;

            for (int i = 0; i < fields.size(); i++) {
                JSONObject field = fields.getJSONObject(i);
                String type = field.getString("type");
                String key = field.getString("key");
                String formula = field.getString("formula");

                if ("formula".equals(type) && formula != null && !formula.isEmpty()) {
                    Object result = evaluateFormula(formula, values);
                    computedValues.put(key, result);
                }
            }
        } catch (Exception e) {
            log.error("Calculate formula error", e);
        }
        return computedValues;
    }

    public Object evaluateFormula(String formula, Map<String, Object> values) {
        String expr = formula;
        List<String> refs = extractFieldReferences(formula);
        for (String ref : refs) {
            Object val = values.get(ref);
            String valStr = val == null ? "0" : val.toString();
            expr = expr.replace("${" + ref + "}", valStr);
        }
        return evaluateExpression(expr);
    }

    private Object evaluateExpression(String expr) {
        try {
            return new ExpressionEvaluator().evaluate(expr);
        } catch (Exception e) {
            log.error("Expression eval error: {}", expr, e);
            return null;
        }
    }

    public static class ExpressionEvaluator {
        public Object evaluate(String expr) {
            expr = expr.trim().replaceAll("\\s+", "");
            return parseAddSub(expr);
        }

        private double parseAddSub(String expr) {
            int depth = 0;
            for (int i = expr.length() - 1; i >= 0; i--) {
                char c = expr.charAt(i);
                if (c == '(') depth++;
                if (c == ')') depth--;
                if (depth == 0 && (c == '+' || c == '-')) {
                    double left = parseAddSub(expr.substring(0, i));
                    double right = parseMulDiv(expr.substring(i + 1));
                    return c == '+' ? left + right : left - right;
                }
            }
            return parseMulDiv(expr);
        }

        private double parseMulDiv(String expr) {
            int depth = 0;
            for (int i = expr.length() - 1; i >= 0; i--) {
                char c = expr.charAt(i);
                if (c == '(') depth++;
                if (c == ')') depth--;
                if (depth == 0 && (c == '*' || c == '/')) {
                    double left = parseMulDiv(expr.substring(0, i));
                    double right = parseValue(expr.substring(i + 1));
                    return c == '*' ? left * right : left / right;
                }
            }
            return parseValue(expr);
        }

        private double parseValue(String expr) {
            if (expr.startsWith("(") && expr.endsWith(")")) {
                return parseAddSub(expr.substring(1, expr.length() - 1));
            }
            if (expr.toUpperCase().startsWith("SUM(")) {
                return parseSum(expr);
            }
            return Double.parseDouble(expr);
        }

        private double parseSum(String expr) {
            return 0;
        }
    }

    public double calculateSubTableSum(JSONArray rows, String columnKey) {
        double sum = 0;
        for (int i = 0; i < rows.size(); i++) {
            JSONObject row = rows.getJSONObject(i);
            Object val = row.get(columnKey);
            if (val instanceof Number) {
                sum += ((Number) val).doubleValue();
            }
        }
        return sum;
    }
}
