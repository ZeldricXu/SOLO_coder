package com.loganalytics.query.ast;

import com.loganalytics.query.ast.ASTNode.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.*;

public class SqlTranslator implements ASTVisitor<String, StringBuilder> {
    private static final Logger log = LoggerFactory.getLogger(SqlTranslator.class);

    private static final Map<String, String> FIELD_MAPPING = Map.of(
            "service", "service_name",
            "service_name", "service_name",
            "level", "level",
            "severity", "level",
            "message", "message",
            "msg", "message",
            "pattern", "pattern_id",
            "pattern_id", "pattern_id",
            "trace", "trace_id",
            "trace_id", "trace_id",
            "host", "hostname",
            "hostname", "hostname",
            "time", "time",
            "timestamp", "time",
            "ts", "time",
            "error_code", "error_code",
            "errorCode", "error_code",
            "team", "team",
            "env", "environment",
            "environment", "environment"
    );

    private final List<Object> parameters = new ArrayList<>();

    public String toSql(ParseResult result) {
        if (!result.isValid()) {
            throw new IllegalArgumentException("Cannot translate invalid query: " + result.getErrors());
        }

        StringBuilder sb = new StringBuilder();
        sb.append("SELECT time, service_name, level, pattern_id, trace_id, hostname, message ");
        sb.append("FROM log_index WHERE ");

        String whereClause = result.getQuery().accept(this, sb);
        sb.append(whereClause);

        TimeRangeNode timeRange = result.getQuery().getTimeRange();
        if (timeRange != null) {
            if (whereClause.length() > 0) {
                sb.append(" AND ");
            }
            String timeClause = timeRange.accept(this, sb);
            sb.append(timeClause);
        }

        sb.append(" ORDER BY time DESC");

        return sb.toString();
    }

    public Map<String, Object> toFilters(ParseResult result) {
        if (!result.isValid()) {
            throw new IllegalArgumentException("Cannot translate invalid query: " + result.getErrors());
        }

        Map<String, Object> filters = new LinkedHashMap<>();
        FilterExtractingVisitor extractor = new FilterExtractingVisitor();
        result.getQuery().getExpression().accept(extractor, filters);

        TimeRangeNode timeRange = result.getQuery().getTimeRange();
        if (timeRange != null) {
            if (timeRange.getStartTime() != null) {
                filters.put("timeStart", timeRange.getStartTime());
            }
            if (timeRange.getEndTime() != null) {
                filters.put("timeEnd", timeRange.getEndTime());
            }
        }

        return filters;
    }

    @Override
    public String visit(QueryNode node, StringBuilder context) {
        return node.getExpression().accept(this, context);
    }

    @Override
    public String visit(AndExpression node, StringBuilder context) {
        String left = node.getLeft().accept(this, context);
        String right = node.getRight().accept(this, context);
        return "(" + left + " AND " + right + ")";
    }

    @Override
    public String visit(OrExpression node, StringBuilder context) {
        String left = node.getLeft().accept(this, context);
        String right = node.getRight().accept(this, context);
        return "(" + left + " OR " + right + ")";
    }

    @Override
    public String visit(NotExpression node, StringBuilder context) {
        String expr = node.getExpression().accept(this, context);
        return "NOT (" + expr + ")";
    }

    @Override
    public String visit(FieldComparisonNode node, StringBuilder context) {
        String field = mapField(node.getFieldName());

        switch (node.getComparisonType()) {
            case EXACT:
                parameters.add(node.getValue().asString());
                return field + " = ?";

            case REGEX:
                parameters.add(node.getValue().asString());
                return field + " ~ ?";

            case RANGE:
                parameters.add(node.getRangeStart().asString());
                parameters.add(node.getRangeEnd().asString());
                return field + " BETWEEN ? AND ?";

            case GREATER_THAN:
                parameters.add(node.getValue().asNumber());
                return field + " > ?";

            case LESS_THAN:
                parameters.add(node.getValue().asNumber());
                return field + " < ?";

            case GREATER_EQUAL:
                parameters.add(node.getValue().asNumber());
                return field + " >= ?";

            case LESS_EQUAL:
                parameters.add(node.getValue().asNumber());
                return field + " <= ?";

            case NOT_EQUAL:
                parameters.add(node.getValue().asString());
                return field + " != ?";

            case EQUAL:
                parameters.add(node.getValue().asString());
                return field + " = ?";

            default:
                throw new IllegalStateException("Unknown comparison type: " + node.getComparisonType());
        }
    }

    @Override
    public String visit(FulltextExpression node, StringBuilder context) {
        parameters.add(node.getQuery());
        return "message_tsvector @@ plainto_tsquery('english', ?)";
    }

    @Override
    public String visit(TimeRangeNode node, StringBuilder context) {
        if (node.getStartTime() != null && node.getEndTime() != null) {
            parameters.add(java.sql.Timestamp.from(node.getStartTime()));
            parameters.add(java.sql.Timestamp.from(node.getEndTime()));
            return "time >= ? AND time <= ?";
        } else if (node.getStartTime() != null) {
            parameters.add(java.sql.Timestamp.from(node.getStartTime()));
            return "time >= ?";
        } else if (node.getEndTime() != null) {
            parameters.add(java.sql.Timestamp.from(node.getEndTime()));
            return "time <= ?";
        }
        return "";
    }

    @Override
    public String visit(ValueNode node, StringBuilder context) {
        return node.toString();
    }

    private String mapField(String fieldName) {
        String mapped = FIELD_MAPPING.getOrDefault(fieldName.toLowerCase(), fieldName);
        if (mapped.equals("level")) {
            return "level::text";
        }
        return mapped;
    }

    public List<Object> getParameters() {
        return parameters;
    }

    private static class FilterExtractingVisitor implements ASTVisitor<Void, Map<String, Object>> {
        @Override
        public Void visit(QueryNode node, Map<String, Object> context) {
            node.getExpression().accept(this, context);
            return null;
        }

        @Override
        public Void visit(AndExpression node, Map<String, Object> context) {
            node.getLeft().accept(this, context);
            node.getRight().accept(this, context);
            return null;
        }

        @Override
        public Void visit(OrExpression node, Map<String, Object> context) {
            node.getLeft().accept(this, context);
            node.getRight().accept(this, context);
            return null;
        }

        @Override
        public Void visit(NotExpression node, Map<String, Object> context) {
            context.put("__not_" + UUID.randomUUID().toString().substring(0, 8),
                    node.getExpression());
            return null;
        }

        @Override
        public Void visit(FieldComparisonNode node, Map<String, Object> context) {
            String field = mapField(node.getFieldName());

            switch (node.getComparisonType()) {
                case EXACT:
                case EQUAL:
                    context.put(field, node.getValue().asString());
                    break;

                case REGEX:
                    context.put(field + "_regex", node.getValue().asString());
                    break;

                case RANGE:
                    context.put(field + "_start", node.getRangeStart().asString());
                    context.put(field + "_end", node.getRangeEnd().asString());
                    break;

                case FULLTEXT:
                    context.put("fulltext", node.getValue().asString());
                    break;

                default:
                    context.put(field, node.getValue().asString());
            }

            return null;
        }

        @Override
        public Void visit(FulltextExpression node, Map<String, Object> context) {
            context.put("fulltext", node.getQuery());
            return null;
        }

        @Override
        public Void visit(TimeRangeNode node, Map<String, Object> context) {
            if (node.getStartTime() != null) {
                context.put("timeStart", node.getStartTime());
            }
            if (node.getEndTime() != null) {
                context.put("timeEnd", node.getEndTime());
            }
            return null;
        }

        @Override
        public Void visit(ValueNode node, Map<String, Object> context) {
            return null;
        }

        private String mapField(String fieldName) {
            return FIELD_MAPPING.getOrDefault(fieldName.toLowerCase(), fieldName);
        }
    }
}
