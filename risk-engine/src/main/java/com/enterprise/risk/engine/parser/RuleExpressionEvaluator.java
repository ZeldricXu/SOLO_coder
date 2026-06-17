package com.enterprise.risk.engine.parser;

import com.enterprise.risk.common.event.RiskEvent;
import com.enterprise.risk.engine.parser.ExpressionTree.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.time.Instant;
import java.util.*;
import java.util.regex.Pattern;

import static com.enterprise.risk.engine.parser.ExpressionTree.*;

/**
 * 规则表达式解释执行器
 * 遍历ExpressionNode树，传入RiskEvent上下文，计算返回Object结果
 * 支持函数调用：abs, min, max, avg, length, contains, startsWith, endsWith, toLower, toUpper, now
 */
public class RuleExpressionEvaluator {

    private static final Logger log = LoggerFactory.getLogger(RuleExpressionEvaluator.class);

    private final Map<String, Object> additionalContext;

    public RuleExpressionEvaluator() {
        this.additionalContext = Map.of();
    }

    public RuleExpressionEvaluator(Map<String, Object> additionalContext) {
        this.additionalContext = additionalContext != null ? additionalContext : Map.of();
    }

    /**
     * 执行表达式，返回boolean结果
     */
    public boolean evaluateAsBoolean(ExpressionNode node, RiskEvent event) {
        Object result = evaluate(node, event);
        return toBoolean(result);
    }

    /**
     * 执行表达式，返回Object结果
     */
    public Object evaluate(ExpressionNode node, RiskEvent event) {
        if (node == null) {
            return null;
        }
        EvaluationVisitor visitor = new EvaluationVisitor(event, additionalContext);
        return node.accept(visitor);
    }

    /**
     * 将任意类型转为boolean
     */
    private boolean toBoolean(Object value) {
        if (value == null) {
            return false;
        }
        if (value instanceof Boolean b) {
            return b;
        }
        if (value instanceof Number n) {
            return n.doubleValue() != 0;
        }
        if (value instanceof String s) {
            return !s.isEmpty();
        }
        if (value instanceof Collection<?> c) {
            return !c.isEmpty();
        }
        return true;
    }

    /**
     * 表达式求值Visitor
     */
    private static class EvaluationVisitor implements ExpressionNodeVisitor<Object> {

        private final RiskEvent event;
        private final Map<String, Object> additionalContext;

        EvaluationVisitor(RiskEvent event, Map<String, Object> additionalContext) {
            this.event = event;
            this.additionalContext = additionalContext;
        }

        @Override
        public Object visit(LiteralNode node) {
            return node.getValue();
        }

        @Override
        public Object visit(FieldAccessNode node) {
            List<String> path = node.getFieldPath();
            if (path.isEmpty()) {
                return null;
            }
            String root = path.get(0);
            Object rootValue = resolveRootValue(root);
            if (rootValue == null) {
                return null;
            }
            if (path.size() == 1) {
                return rootValue;
            }
            return resolveNestedPath(rootValue, path.subList(1, path.size()));
        }

        private Object resolveRootValue(String root) {
            return switch (root) {
                case "event" -> event;
                case "entity" -> resolveEntityContext();
                case "user" -> resolveUserContext();
                case "session" -> resolveSessionContext();
                case "context" -> additionalContext;
                default -> event.getAttribute(root);
            };
        }

        private Map<String, Object> resolveEntityContext() {
            Map<String, Object> ctx = new HashMap<>();
            ctx.put("id", event.getEntityId());
            ctx.put("type", event.getEntityType());
            return ctx;
        }

        private Map<String, Object> resolveUserContext() {
            Map<String, Object> ctx = new HashMap<>();
            ctx.put("id", event.getUserId());
            Map<String, Object> attrs = event.getAttributes();
            if (attrs != null) {
                for (Map.Entry<String, Object> e : attrs.entrySet()) {
                    if (e.getKey().startsWith("user_")) {
                        ctx.put(e.getKey().substring(5), e.getValue());
                    }
                }
            }
            return ctx;
        }

        private Map<String, Object> resolveSessionContext() {
            Map<String, Object> ctx = new HashMap<>();
            ctx.put("id", event.getSessionId());
            return ctx;
        }

        @SuppressWarnings("unchecked")
        private Object resolveNestedPath(Object obj, List<String> path) {
            Object current = obj;
            for (String field : path) {
                if (current == null) {
                    return null;
                }
                if (current instanceof Map<?, ?> map) {
                    current = ((Map<String, Object>) map).get(field);
                } else if (current instanceof RiskEvent re) {
                    current = getFieldFromRiskEvent(re, field);
                } else {
                    current = getFieldViaReflection(current, field);
                }
            }
            return current;
        }

        private Object getFieldFromRiskEvent(RiskEvent re, String field) {
            return switch (field) {
                case "eventId", "event_id" -> re.getEventId();
                case "eventType", "event_type" -> re.getEventType();
                case "businessLine", "business_line" -> re.getBusinessLine();
                case "timestamp" -> re.getTimestamp();
                case "entityId", "entity_id" -> re.getEntityId();
                case "entityType", "entity_type" -> re.getEntityType();
                case "source" -> re.getSource();
                case "sessionId", "session_id" -> re.getSessionId();
                case "ip" -> re.getIp();
                case "userId", "user_id" -> re.getUserId();
                default -> re.getAttribute(field);
            };
        }

        private Object getFieldViaReflection(Object obj, String field) {
            try {
                Class<?> clazz = obj.getClass();
                Field f = clazz.getDeclaredField(field);
                f.setAccessible(true);
                return f.get(obj);
            } catch (NoSuchFieldException e) {
                return null;
            } catch (IllegalAccessException e) {
                log.warn("反射访问字段失败: {}", field, e);
                return null;
            }
        }

        @Override
        public Object visit(BinaryOpNode node) {
            Object left = node.getLeft().accept(this);
            BinaryOpNode.BinaryOperator op = node.getOperator();

            if (op == BinaryOpNode.BinaryOperator.AND) {
                if (!toBoolean(left)) return false;
                return toBoolean(node.getRight().accept(this));
            }
            if (op == BinaryOpNode.BinaryOperator.OR) {
                if (toBoolean(left)) return true;
                return toBoolean(node.getRight().accept(this));
            }

            Object right = node.getRight().accept(this);

            return switch (op) {
                case GT -> compare(left, right) > 0;
                case GTE -> compare(left, right) >= 0;
                case LT -> compare(left, right) < 0;
                case LTE -> compare(left, right) <= 0;
                case EQ -> objectsEqual(left, right);
                case NEQ -> !objectsEqual(left, right);
                case ADD -> add(left, right);
                case SUBTRACT -> subtract(left, right);
                case MULTIPLY -> multiply(left, right);
                case DIVIDE -> divide(left, right);
                case MODULO -> modulo(left, right);
                default -> null;
            };
        }

        @Override
        public Object visit(UnaryOpNode node) {
            Object value = node.getOperand().accept(this);
            return switch (node.getOperator()) {
                case NOT -> !toBoolean(value);
                case NEGATE -> negate(value);
            };
        }

        @Override
        @SuppressWarnings("unchecked")
        public Object visit(FunctionCallNode node) {
            String name = node.getFunctionName();
            List<Object> args = node.getArguments().stream()
                    .map(a -> a.accept(this))
                    .toList();

            return switch (name) {
                case "abs" -> abs(args);
                case "min" -> min(args);
                case "max" -> max(args);
                case "avg" -> avg(args);
                case "length" -> length(args);
                case "contains" -> contains(args);
                case "startsWith" -> startsWith(args);
                case "endsWith" -> endsWith(args);
                case "toLower" -> toLower(args);
                case "toUpper" -> toUpper(args);
                case "now" -> now();
                default -> {
                    log.warn("未知函数: {}", name);
                    yield null;
                }
            };
        }

        @Override
        public Object visit(InNode node) {
            Object target = node.getTarget().accept(this);
            Set<Object> valueSet = new HashSet<>();
            for (ExpressionNode vn : node.getValues()) {
                valueSet.add(vn.accept(this));
            }
            boolean contained = valueSet.contains(target);
            return node.isNegated() ? !contained : contained;
        }

        @Override
        public Object visit(BetweenNode node) {
            Object target = node.getTarget().accept(this);
            Object lower = node.getLowerBound().accept(this);
            Object upper = node.getUpperBound().accept(this);
            boolean inRange = compare(target, lower) >= 0 && compare(target, upper) <= 0;
            return node.isNegated() ? !inRange : inRange;
        }

        @Override
        public Object visit(LikeNode node) {
            Object target = node.getTarget().accept(this);
            if (target == null) {
                return node.isNegated();
            }
            String str = target.toString();
            String regex = likePatternToRegex(node.getPattern());
            boolean matched = Pattern.matches(regex, str);
            return node.isNegated() ? !matched : matched;
        }

        @Override
        public Object visit(NullCheckNode node) {
            Object target = node.getTarget().accept(this);
            boolean isNull = target == null;
            return node.isCheckNull() ? isNull : !isNull;
        }

        @SuppressWarnings("unchecked")
        private int compare(Object a, Object b) {
            if (a == null && b == null) return 0;
            if (a == null) return -1;
            if (b == null) return 1;

            if (a instanceof Number na && b instanceof Number nb) {
                return Double.compare(na.doubleValue(), nb.doubleValue());
            }
            if (a instanceof Comparable ca && b instanceof Comparable cb
                    && a.getClass().isInstance(b)) {
                try {
                    return ca.compareTo(cb);
                } catch (ClassCastException e) {
                    return a.toString().compareTo(b.toString());
                }
            }
            return a.toString().compareTo(b.toString());
        }

        private boolean objectsEqual(Object a, Object b) {
            if (a == null && b == null) return true;
            if (a == null || b == null) return false;
            if (a instanceof Number na && b instanceof Number nb) {
                return Double.compare(na.doubleValue(), nb.doubleValue()) == 0;
            }
            return a.equals(b);
        }

        private Object add(Object a, Object b) {
            if (a instanceof Number na && b instanceof Number nb) {
                if (a instanceof Double || b instanceof Double || a instanceof Float || b instanceof Float) {
                    return na.doubleValue() + nb.doubleValue();
                }
                return na.longValue() + nb.longValue();
            }
            if (a instanceof String || b instanceof String) {
                return String.valueOf(a) + String.valueOf(b);
            }
            return null;
        }

        private Object subtract(Object a, Object b) {
            if (a instanceof Number na && b instanceof Number nb) {
                if (a instanceof Double || b instanceof Double || a instanceof Float || b instanceof Float) {
                    return na.doubleValue() - nb.doubleValue();
                }
                return na.longValue() - nb.longValue();
            }
            return null;
        }

        private Object multiply(Object a, Object b) {
            if (a instanceof Number na && b instanceof Number nb) {
                if (a instanceof Double || b instanceof Double || a instanceof Float || b instanceof Float) {
                    return na.doubleValue() * nb.doubleValue();
                }
                return na.longValue() * nb.longValue();
            }
            return null;
        }

        private Object divide(Object a, Object b) {
            if (a instanceof Number na && b instanceof Number nb) {
                double db = nb.doubleValue();
                if (db == 0) return null;
                return na.doubleValue() / db;
            }
            return null;
        }

        private Object modulo(Object a, Object b) {
            if (a instanceof Number na && b instanceof Number nb) {
                if (a instanceof Double || b instanceof Double || a instanceof Float || b instanceof Float) {
                    return na.doubleValue() % nb.doubleValue();
                }
                return na.longValue() % nb.longValue();
            }
            return null;
        }

        private Object negate(Object value) {
            if (value instanceof Number n) {
                if (value instanceof Double d) return -d;
                if (value instanceof Float f) return -f;
                if (value instanceof Long l) return -l;
                return -n.intValue();
            }
            return null;
        }

        private Object abs(List<Object> args) {
            if (args.isEmpty()) return null;
            Object v = args.get(0);
            if (v instanceof Number n) {
                if (v instanceof Double d) return Math.abs(d);
                if (v instanceof Float f) return Math.abs(f);
                if (v instanceof Long l) return Math.abs(l);
                return Math.abs(n.intValue());
            }
            return null;
        }

        private Object min(List<Object> args) {
            if (args.isEmpty()) return null;
            Object result = args.get(0);
            for (int i = 1; i < args.size(); i++) {
                if (compare(result, args.get(i)) > 0) {
                    result = args.get(i);
                }
            }
            return result;
        }

        private Object max(List<Object> args) {
            if (args.isEmpty()) return null;
            Object result = args.get(0);
            for (int i = 1; i < args.size(); i++) {
                if (compare(result, args.get(i)) < 0) {
                    result = args.get(i);
                }
            }
            return result;
        }

        private Object avg(List<Object> args) {
            if (args.isEmpty()) return null;
            double sum = 0.0;
            int count = 0;
            for (Object arg : args) {
                if (arg instanceof Number n) {
                    sum += n.doubleValue();
                    count++;
                }
            }
            return count == 0 ? null : sum / count;
        }

        private Object length(List<Object> args) {
            if (args.isEmpty()) return null;
            Object v = args.get(0);
            if (v instanceof String s) return s.length();
            if (v instanceof Collection<?> c) return c.size();
            if (v instanceof Map<?, ?> m) return m.size();
            if (v != null && v.getClass().isArray()) return java.lang.reflect.Array.getLength(v);
            return 0;
        }

        private Object contains(List<Object> args) {
            if (args.size() < 2) return false;
            Object container = args.get(0);
            Object target = args.get(1);
            if (container instanceof String s1 && target instanceof String s2) {
                return s1.contains(s2);
            }
            if (container instanceof Collection<?> c) {
                return c.contains(target);
            }
            return false;
        }

        private Object startsWith(List<Object> args) {
            if (args.size() < 2) return false;
            Object s = args.get(0);
            Object prefix = args.get(1);
            if (s instanceof String str && prefix instanceof String p) {
                return str.startsWith(p);
            }
            return false;
        }

        private Object endsWith(List<Object> args) {
            if (args.size() < 2) return false;
            Object s = args.get(0);
            Object suffix = args.get(1);
            if (s instanceof String str && suffix instanceof String su) {
                return str.endsWith(su);
            }
            return false;
        }

        private Object toLower(List<Object> args) {
            if (args.isEmpty()) return null;
            Object v = args.get(0);
            return v instanceof String s ? s.toLowerCase() : null;
        }

        private Object toUpper(List<Object> args) {
            if (args.isEmpty()) return null;
            Object v = args.get(0);
            return v instanceof String s ? s.toUpperCase() : null;
        }

        private Object now() {
            return Instant.now().toEpochMilli();
        }

        private String likePatternToRegex(String pattern) {
            StringBuilder sb = new StringBuilder("^");
            for (int i = 0; i < pattern.length(); i++) {
                char c = pattern.charAt(i);
                switch (c) {
                    case '%' -> sb.append(".*");
                    case '_' -> sb.append('.');
                    case '\\' -> {
                        if (i + 1 < pattern.length()) {
                        sb.append(Pattern.quote(String.valueOf(pattern.charAt(++i))));
                    } else {
                        sb.append("\\\\");
                    }
                }
                default -> sb.append(Pattern.quote(String.valueOf(c)));
            }
            sb.append("$");
            return sb.toString();
        }
    }
}
