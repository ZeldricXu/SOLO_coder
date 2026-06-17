package com.enterprise.risk.engine.parser;

import lombok.Getter;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * 表达式树节点类层次结构
 * 使用Visitor模式支持遍历和操作
 */
public interface ExpressionTree {

    /**
     * 表达式节点访问者接口
     */
    interface ExpressionNodeVisitor<R> {
        R visit(LiteralNode node);
        R visit(FieldAccessNode node);
        R visit(BinaryOpNode node);
        R visit(UnaryOpNode node);
        R visit(FunctionCallNode node);
        R visit(InNode node);
        R visit(BetweenNode node);
        R visit(LikeNode node);
        R visit(NullCheckNode node);
    }

    /**
     * 抽象表达式节点基类
     */
    @Getter
    abstract class ExpressionNode implements Serializable {
        private final int line;
        private final int column;

        protected ExpressionNode() {
            this.line = -1;
            this.column = -1;
        }

        protected ExpressionNode(int line, int column) {
            this.line = line;
            this.column = column;
        }

        public abstract <R> R accept(ExpressionNodeVisitor<R> visitor);
    }

    /**
     * 字面量节点：数字、字符串、布尔值、null
     */
    @Getter
    class LiteralNode extends ExpressionNode {
        public enum LiteralType {
            NUMBER, STRING, BOOLEAN, NULL
        }

        private final LiteralType type;
        private final Object value;

        public LiteralNode(LiteralType type, Object value) {
            super();
            this.type = type;
            this.value = value;
        }

        public LiteralNode(LiteralType type, Object value, int line, int column) {
            super(line, column);
            this.type = type;
            this.value = value;
        }

        @Override
        public <R> R accept(ExpressionNodeVisitor<R> visitor) {
            return visitor.visit(this);
        }
    }

    /**
     * 字段访问节点：支持嵌套属性访问，如 event.amount、user.profile.age
     */
    @Getter
    class FieldAccessNode extends ExpressionNode {
        private final List<String> fieldPath;

        public FieldAccessNode(List<String> fieldPath) {
            super();
            this.fieldPath = new ArrayList<>(fieldPath);
        }

        public FieldAccessNode(List<String> fieldPath, int line, int column) {
            super(line, column);
            this.fieldPath = new ArrayList<>(fieldPath);
        }

        public String getRootField() {
            return fieldPath.isEmpty() ? null : fieldPath.get(0);
        }

        public List<String> getNestedPath() {
            return fieldPath.size() <= 1 ? List.of() : fieldPath.subList(1, fieldPath.size());
        }

        public String getFullPath() {
            return String.join(".", fieldPath);
        }

        @Override
        public <R> R accept(ExpressionNodeVisitor<R> visitor) {
            return visitor.visit(this);
        }
    }

    /**
     * 二元操作节点：AND、OR、比较运算（>、>=、<、<=、==、!=）、算术运算（+、-、*、/）
     */
    @Getter
    class BinaryOpNode extends ExpressionNode {
        public enum BinaryOperator {
            AND, OR,
            GT, GTE, LT, LTE, EQ, NEQ,
            ADD, SUBTRACT, MULTIPLY, DIVIDE, MODULO
        }

        private final BinaryOperator operator;
        private final ExpressionNode left;
        private final ExpressionNode right;

        public BinaryOpNode(BinaryOperator operator, ExpressionNode left, ExpressionNode right) {
            super();
            this.operator = operator;
            this.left = left;
            this.right = right;
        }

        public BinaryOpNode(BinaryOperator operator, ExpressionNode left, ExpressionNode right, int line, int column) {
            super(line, column);
            this.operator = operator;
            this.left = left;
            this.right = right;
        }

        public boolean isLogical() {
            return operator == BinaryOperator.AND || operator == BinaryOperator.OR;
        }

        public boolean isComparison() {
            return operator == BinaryOperator.GT || operator == BinaryOperator.GTE
                    || operator == BinaryOperator.LT || operator == BinaryOperator.LTE
                    || operator == BinaryOperator.EQ || operator == BinaryOperator.NEQ;
        }

        public boolean isArithmetic() {
            return operator == BinaryOperator.ADD || operator == BinaryOperator.SUBTRACT
                    || operator == BinaryOperator.MULTIPLY || operator == BinaryOperator.DIVIDE
                    || operator == BinaryOperator.MODULO;
        }

        @Override
        public <R> R accept(ExpressionNodeVisitor<R> visitor) {
            return visitor.visit(this);
        }
    }

    /**
     * 一元操作节点：NOT、负号
     */
    @Getter
    class UnaryOpNode extends ExpressionNode {
        public enum UnaryOperator {
            NOT, NEGATE
        }

        private final UnaryOperator operator;
        private final ExpressionNode operand;

        public UnaryOpNode(UnaryOperator operator, ExpressionNode operand) {
            super();
            this.operator = operator;
            this.operand = operand;
        }

        public UnaryOpNode(UnaryOperator operator, ExpressionNode operand, int line, int column) {
            super(line, column);
            this.operator = operator;
            this.operand = operand;
        }

        @Override
        public <R> R accept(ExpressionNodeVisitor<R> visitor) {
            return visitor.visit(this);
        }
    }

    /**
     * 函数调用节点：abs、min、max、avg、length、contains、startsWith、endsWith、toLower、toUpper、now
     */
    @Getter
    class FunctionCallNode extends ExpressionNode {
        private final String functionName;
        private final List<ExpressionNode> arguments;

        public FunctionCallNode(String functionName, List<ExpressionNode> arguments) {
            super();
            this.functionName = functionName;
            this.arguments = new ArrayList<>(arguments);
        }

        public FunctionCallNode(String functionName, List<ExpressionNode> arguments, int line, int column) {
            super(line, column);
            this.functionName = functionName;
            this.arguments = new ArrayList<>(arguments);
        }

        public int getArgumentCount() {
            return arguments.size();
        }

        @Override
        public <R> R accept(ExpressionNodeVisitor<R> visitor) {
            return visitor.visit(this);
        }
    }

    /**
     * IN操作节点：检查值是否在列表中
     */
    @Getter
    class InNode extends ExpressionNode {
        private final ExpressionNode target;
        private final List<ExpressionNode> values;
        private final boolean negated;

        public InNode(ExpressionNode target, List<ExpressionNode> values, boolean negated) {
            super();
            this.target = target;
            this.values = new ArrayList<>(values);
            this.negated = negated;
        }

        public InNode(ExpressionNode target, List<ExpressionNode> values, boolean negated, int line, int column) {
            super(line, column);
            this.target = target;
            this.values = new ArrayList<>(values);
            this.negated = negated;
        }

        @Override
        public <R> R accept(ExpressionNodeVisitor<R> visitor) {
            return visitor.visit(this);
        }
    }

    /**
     * BETWEEN操作节点：检查值是否在闭区间内
     */
    @Getter
    class BetweenNode extends ExpressionNode {
        private final ExpressionNode target;
        private final ExpressionNode lowerBound;
        private final ExpressionNode upperBound;
        private final boolean negated;

        public BetweenNode(ExpressionNode target, ExpressionNode lowerBound, ExpressionNode upperBound, boolean negated) {
            super();
            this.target = target;
            this.lowerBound = lowerBound;
            this.upperBound = upperBound;
            this.negated = negated;
        }

        public BetweenNode(ExpressionNode target, ExpressionNode lowerBound, ExpressionNode upperBound,
                           boolean negated, int line, int column) {
            super(line, column);
            this.target = target;
            this.lowerBound = lowerBound;
            this.upperBound = upperBound;
            this.negated = negated;
        }

        @Override
        public <R> R accept(ExpressionNodeVisitor<R> visitor) {
            return visitor.visit(this);
        }
    }

    /**
     * LIKE操作节点：SQL风格的模式匹配
     */
    @Getter
    class LikeNode extends ExpressionNode {
        private final ExpressionNode target;
        private final String pattern;
        private final boolean negated;

        public LikeNode(ExpressionNode target, String pattern, boolean negated) {
            super();
            this.target = target;
            this.pattern = pattern;
            this.negated = negated;
        }

        public LikeNode(ExpressionNode target, String pattern, boolean negated, int line, int column) {
            super(line, column);
            this.target = target;
            this.pattern = pattern;
            this.negated = negated;
        }

        @Override
        public <R> R accept(ExpressionNodeVisitor<R> visitor) {
            return visitor.visit(this);
        }
    }

    /**
     * NULL检查节点：IS NULL / IS NOT NULL
     */
    @Getter
    class NullCheckNode extends ExpressionNode {
        private final ExpressionNode target;
        private final boolean checkNull;

        public NullCheckNode(ExpressionNode target, boolean checkNull) {
            super();
            this.target = target;
            this.checkNull = checkNull;
        }

        public NullCheckNode(ExpressionNode target, boolean checkNull, int line, int column) {
            super(line, column);
            this.target = target;
            this.checkNull = checkNull;
        }

        @Override
        public <R> R accept(ExpressionNodeVisitor<R> visitor) {
            return visitor.visit(this);
        }
    }
}
