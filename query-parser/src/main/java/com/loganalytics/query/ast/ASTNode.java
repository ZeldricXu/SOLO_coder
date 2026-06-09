package com.loganalytics.query.ast;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public abstract class ASTNode {
    public abstract <R, C> R accept(ASTVisitor<R, C> visitor, C context);

    public interface ASTVisitor<R, C> {
        R visit(QueryNode node, C context);
        R visit(AndExpression node, C context);
        R visit(OrExpression node, C context);
        R visit(NotExpression node, C context);
        R visit(FieldComparisonNode node, C context);
        R visit(FulltextExpression node, C context);
        R visit(TimeRangeNode node, C context);
        R visit(ValueNode node, C context);
    }

    public static class QueryNode extends ASTNode {
        private final ExpressionNode expression;
        private final TimeRangeNode timeRange;

        public QueryNode(ExpressionNode expression, TimeRangeNode timeRange) {
            this.expression = expression;
            this.timeRange = timeRange;
        }

        public ExpressionNode getExpression() { return expression; }
        public TimeRangeNode getTimeRange() { return timeRange; }

        @Override
        public <R, C> R accept(ASTVisitor<R, C> visitor, C context) {
            return visitor.visit(this, context);
        }
    }

    public abstract static class ExpressionNode extends ASTNode {}

    public static class AndExpression extends ExpressionNode {
        private final ExpressionNode left;
        private final ExpressionNode right;

        public AndExpression(ExpressionNode left, ExpressionNode right) {
            this.left = left;
            this.right = right;
        }

        public ExpressionNode getLeft() { return left; }
        public ExpressionNode getRight() { return right; }

        @Override
        public <R, C> R accept(ASTVisitor<R, C> visitor, C context) {
            return visitor.visit(this, context);
        }
    }

    public static class OrExpression extends ExpressionNode {
        private final ExpressionNode left;
        private final ExpressionNode right;

        public OrExpression(ExpressionNode left, ExpressionNode right) {
            this.left = left;
            this.right = right;
        }

        public ExpressionNode getLeft() { return left; }
        public ExpressionNode getRight() { return right; }

        @Override
        public <R, C> R accept(ASTVisitor<R, C> visitor, C context) {
            return visitor.visit(this, context);
        }
    }

    public static class NotExpression extends ExpressionNode {
        private final ExpressionNode expression;

        public NotExpression(ExpressionNode expression) {
            this.expression = expression;
        }

        public ExpressionNode getExpression() { return expression; }

        @Override
        public <R, C> R accept(ASTVisitor<R, C> visitor, C context) {
            return visitor.visit(this, context);
        }
    }

    public enum ComparisonType {
        EXACT, REGEX, RANGE, GREATER_THAN, LESS_THAN, GREATER_EQUAL, LESS_EQUAL, NOT_EQUAL, EQUAL
    }

    public static class FieldComparisonNode extends ExpressionNode {
        private final String fieldName;
        private final ComparisonType comparisonType;
        private final ValueNode value;
        private final ValueNode rangeStart;
        private final ValueNode rangeEnd;

        public FieldComparisonNode(String fieldName, ComparisonType type, ValueNode value) {
            this.fieldName = fieldName;
            this.comparisonType = type;
            this.value = value;
            this.rangeStart = null;
            this.rangeEnd = null;
        }

        public FieldComparisonNode(String fieldName, ValueNode rangeStart, ValueNode rangeEnd) {
            this.fieldName = fieldName;
            this.comparisonType = ComparisonType.RANGE;
            this.value = null;
            this.rangeStart = rangeStart;
            this.rangeEnd = rangeEnd;
        }

        public String getFieldName() { return fieldName; }
        public ComparisonType getComparisonType() { return comparisonType; }
        public ValueNode getValue() { return value; }
        public ValueNode getRangeStart() { return rangeStart; }
        public ValueNode getRangeEnd() { return rangeEnd; }

        @Override
        public <R, C> R accept(ASTVisitor<R, C> visitor, C context) {
            return visitor.visit(this, context);
        }
    }

    public static class FulltextExpression extends ExpressionNode {
        private final String query;

        public FulltextExpression(String query) {
            this.query = query;
        }

        public String getQuery() { return query; }

        @Override
        public <R, C> R accept(ASTVisitor<R, C> visitor, C context) {
            return visitor.visit(this, context);
        }
    }

    public enum TimeRangeType {
        SINCE_AGO, BETWEEN, TO
    }

    public static class TimeRangeNode extends ASTNode {
        private final TimeRangeType type;
        private final Instant startTime;
        private final Instant endTime;
        private final long durationMillis;

        public TimeRangeNode(TimeRangeType type, Instant startTime, Instant endTime) {
            this.type = type;
            this.startTime = startTime;
            this.endTime = endTime;
            this.durationMillis = 0;
        }

        public TimeRangeNode(long durationMillis) {
            this.type = TimeRangeType.SINCE_AGO;
            this.durationMillis = durationMillis;
            this.startTime = Instant.now().minusMillis(durationMillis);
            this.endTime = Instant.now();
        }

        public TimeRangeType getType() { return type; }
        public Instant getStartTime() { return startTime; }
        public Instant getEndTime() { return endTime; }
        public long getDurationMillis() { return durationMillis; }

        @Override
        public <R, C> R accept(ASTVisitor<R, C> visitor, C context) {
            return visitor.visit(this, context);
        }
    }

    public enum ValueType {
        STRING, NUMBER, BOOLEAN, NULL
    }

    public static class ValueNode extends ASTNode {
        private final ValueType type;
        private final Object value;

        public ValueNode(String value) {
            this.type = ValueType.STRING;
            this.value = value;
        }

        public ValueNode(Number value) {
            this.type = ValueType.NUMBER;
            this.value = value;
        }

        public ValueNode(boolean value) {
            this.type = ValueType.BOOLEAN;
            this.value = value;
        }

        public ValueType getType() { return type; }
        public Object getValue() { return value; }

        public String asString() {
            return value != null ? value.toString() : null;
        }

        public Number asNumber() {
            if (type == ValueType.NUMBER) return (Number) value;
            if (type == ValueType.STRING && value != null) {
                try {
                    return Double.parseDouble(value.toString());
                } catch (NumberFormatException e) {
                    return null;
                }
            }
            return null;
        }

        public Boolean asBoolean() {
            if (type == ValueType.BOOLEAN) return (Boolean) value;
            if (type == ValueType.STRING && value != null) {
                return Boolean.parseBoolean(value.toString());
            }
            return null;
        }

        @Override
        public <R, C> R accept(ASTVisitor<R, C> visitor, C context) {
            return visitor.visit(this, context);
        }

        @Override
        public String toString() {
            return value != null ? value.toString() : "null";
        }
    }

    public static class ParseResult {
        private final QueryNode query;
        private final List<String> errors;

        public ParseResult(QueryNode query, List<String> errors) {
            this.query = query;
            this.errors = errors;
        }

        public boolean isValid() { return errors == null || errors.isEmpty(); }
        public QueryNode getQuery() { return query; }
        public List<String> getErrors() { return errors; }

        public Map<String, Object> toFilters() {
            return new SqlTranslator().toFilters(this);
        }

        public String toSql() {
            return new SqlTranslator().toSql(this);
        }
    }
}
