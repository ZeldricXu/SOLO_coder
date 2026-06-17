package com.enterprise.risk.engine.parser;

import com.enterprise.risk.common.exception.RuleCompilationException;
import com.enterprise.risk.engine.parser.ExpressionTree.*;
import org.antlr.v4.runtime.*;
import org.antlr.v4.runtime.tree.TerminalNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.stream.Collectors;

import static com.enterprise.risk.engine.parser.ExpressionTree.*;

/**
 * 规则表达式编译器
 * 使用ANTLR生成的RuleExpressionVisitor遍历ParseTree，构建ExpressionNode树
 * 编译时进行静态检查：语法校验、函数存在性校验、参数数量校验、字段引用校验
 */
public class RuleExpressionCompiler {

    private static final Logger log = LoggerFactory.getLogger(RuleExpressionCompiler.class);

    private static final Set<String> SUPPORTED_FUNCTIONS = Set.of(
            "abs", "min", "max", "avg", "length", "contains",
            "startsWith", "endsWith", "toLower", "toUpper", "now"
    );

    private static final Map<String, int[]> FUNCTION_ARGUMENT_RANGES = Map.of(
            "abs", new int[]{1, 1},
            "min", new int[]{1, Integer.MAX_VALUE},
            "max", new int[]{1, Integer.MAX_VALUE},
            "avg", new int[]{1, Integer.MAX_VALUE},
            "length", new int[]{1, 1},
            "contains", new int[]{2, 2},
            "startsWith", new int[]{2, 2},
            "endsWith", new int[]{2, 2},
            "toLower", new int[]{1, 1},
            "toUpper", new int[]{1, 1},
            "now", new int[]{0, 0}
    );

    private static final Set<String> RESERVED_FIELDS = Set.of(
            "event", "entity", "user", "session", "context"
    );

    /**
     * 编译DSL表达式为表达式树
     *
     * @param dslExpression DSL表达式字符串
     * @return 编译后的表达式树根节点
     * @throws RuleCompilationException 编译失败时抛出
     */
    public ExpressionNode compile(String dslExpression) {
        if (dslExpression == null || dslExpression.trim().isEmpty()) {
            throw new RuleCompilationException("表达式不能为空");
        }

        try {
            CharStream input = CharStreams.fromString(dslExpression);
            RuleExpressionLexer lexer = new RuleExpressionLexer(input);
            lexer.removeErrorListeners();
            lexer.addErrorListener(new CompilationErrorListener());

            CommonTokenStream tokens = new CommonTokenStream(lexer);
            RuleExpressionParser parser = new RuleExpressionParser(tokens);
            parser.removeErrorListeners();
            parser.addErrorListener(new CompilationErrorListener());

            RuleExpressionParser.ExpressionContext tree = parser.expression();
            AstBuildingVisitor visitor = new AstBuildingVisitor();
            ExpressionNode root = visitor.visit(tree);

            performStaticAnalysis(root);

            log.debug("表达式编译成功: {}", dslExpression);
            return root;
        } catch (RuleCompilationException e) {
            throw e;
        } catch (Exception e) {
            throw new RuleCompilationException("表达式编译失败: " + e.getMessage(), e);
        }
    }

    /**
     * 执行静态分析
     */
    private void performStaticAnalysis(ExpressionNode root) {
        StaticAnalysisVisitor analyzer = new StaticAnalysisVisitor();
        root.accept(analyzer);

        List<String> errors = analyzer.getErrors();
        if (!errors.isEmpty()) {
            throw new RuleCompilationException("静态检查失败: " + String.join("; ", errors));
        }
    }

    /**
     * 编译错误监听器
     */
    private static class CompilationErrorListener extends BaseErrorListener {
        @Override
        public void syntaxError(Recognizer<?, ?> recognizer, Object offendingSymbol,
                                int line, int charPositionInLine, String msg, RecognitionException e) {
            throw new RuleCompilationException(
                    String.format("语法错误 [行%d, 列%d]: %s", line, charPositionInLine, msg));
        }
    }

    /**
     * AST构建Visitor：遍历ParseTree构建ExpressionNode树
     */
    private static class AstBuildingVisitor extends RuleExpressionBaseVisitor<ExpressionNode> {

        @Override
        public ExpressionNode visitExpression(RuleExpressionParser.ExpressionContext ctx) {
            return visit(ctx.orExpression());
        }

        @Override
        public ExpressionNode visitOrExpression(RuleExpressionParser.OrExpressionContext ctx) {
            List<RuleExpressionParser.AndExpressionContext> andExprs = ctx.andExpression();
            if (andExprs.size() == 1) {
                return visit(andExprs.get(0));
            }

            ExpressionNode result = visit(andExprs.get(0));
            int line = ctx.getStart().getLine();
            int col = ctx.getStart().getCharPositionInLine();
            for (int i = 1; i < andExprs.size(); i++) {
                result = new BinaryOpNode(
                        BinaryOpNode.BinaryOperator.OR,
                        result,
                        visit(andExprs.get(i)),
                        line, col
                );
            }
            return result;
        }

        @Override
        public ExpressionNode visitAndExpression(RuleExpressionParser.AndExpressionContext ctx) {
            List<RuleExpressionParser.NotExpressionContext> notExprs = ctx.notExpression();
            if (notExprs.size() == 1) {
                return visit(notExprs.get(0));
            }

            ExpressionNode result = visit(notExprs.get(0));
            int line = ctx.getStart().getLine();
            int col = ctx.getStart().getCharPositionInLine();
            for (int i = 1; i < notExprs.size(); i++) {
                result = new BinaryOpNode(
                        BinaryOpNode.BinaryOperator.AND,
                        result,
                        visit(notExprs.get(i)),
                        line, col
                );
            }
            return result;
        }

        @Override
        public ExpressionNode visitNotExpression(RuleExpressionParser.NotExpressionContext ctx) {
            if (ctx.NOT() != null) {
                int line = ctx.getStart().getLine();
                int col = ctx.getStart().getCharPositionInLine();
                return new UnaryOpNode(
                        UnaryOpNode.UnaryOperator.NOT,
                        visit(ctx.notExpression()),
                        line, col
                );
            }
            return visit(ctx.comparisonExpression());
        }

        @Override
        public ExpressionNode visitComparisonExpression(RuleExpressionParser.ComparisonExpressionContext ctx) {
            int line = ctx.getStart().getLine();
            int col = ctx.getStart().getCharPositionInLine();

            if (ctx.IN() != null) {
                ExpressionNode target = visit(ctx.primaryExpression(0));
                List<ExpressionNode> values = ctx.valueList() != null
                        ? visitValueList(ctx.valueList())
                        : List.of();
                boolean negated = ctx.NOT() != null;
                return new InNode(target, values, negated, line, col);
            }

            if (ctx.BETWEEN() != null) {
                ExpressionNode target = visit(ctx.primaryExpression(0));
                ExpressionNode lower = visit(ctx.primaryExpression(1));
                ExpressionNode upper = visit(ctx.primaryExpression(2));
                return new BetweenNode(target, lower, upper, false, line, col);
            }

            if (ctx.LIKE() != null) {
                ExpressionNode target = visit(ctx.primaryExpression(0));
                String pattern = unquoteString(ctx.STRING().getText());
                return new LikeNode(target, pattern, false, line, col);
            }

            if (ctx.IS() != null) {
                ExpressionNode target = visit(ctx.primaryExpression(0));
                boolean checkNull = ctx.NOT() == null;
                return new NullCheckNode(target, checkNull, line, col);
            }

            if (ctx.comparisonOperator() != null) {
                BinaryOpNode.BinaryOperator op = mapComparisonOperator(ctx.comparisonOperator());
                return new BinaryOpNode(
                        op,
                        visit(ctx.primaryExpression(0)),
                        visit(ctx.primaryExpression(1)),
                        line, col
                );
            }

            return visit(ctx.primaryExpression(0));
        }

        @Override
        public List<ExpressionNode> visitValueList(RuleExpressionParser.ValueListContext ctx) {
            return ctx.primaryExpression().stream()
                    .map(this::visit)
                    .collect(Collectors.toList());
        }

        @Override
        public ExpressionNode visitPrimaryExpression(RuleExpressionParser.PrimaryExpressionContext ctx) {
            if (ctx.LPAREN() != null) {
                return visit(ctx.orExpression());
            }
            if (ctx.functionCall() != null) {
                return visit(ctx.functionCall());
            }
            if (ctx.fieldAccess() != null) {
                return visit(ctx.fieldAccess());
            }
            return visit(ctx.literal());
        }

        @Override
        public ExpressionNode visitFunctionCall(RuleExpressionParser.FunctionCallContext ctx) {
            String functionName = ctx.IDENTIFIER().getText();
            List<ExpressionNode> args = ctx.argumentList() != null
                    ? visitArgumentList(ctx.argumentList())
                    : List.of();
            int line = ctx.getStart().getLine();
            int col = ctx.getStart().getCharPositionInLine();
            return new FunctionCallNode(functionName, args, line, col);
        }

        @Override
        public List<ExpressionNode> visitArgumentList(RuleExpressionParser.ArgumentListContext ctx) {
            return ctx.primaryExpression().stream()
                    .map(this::visit)
                    .collect(Collectors.toList());
        }

        @Override
        public ExpressionNode visitFieldAccess(RuleExpressionParser.FieldAccessContext ctx) {
            List<String> path = ctx.IDENTIFIER().stream()
                    .map(TerminalNode::getText)
                    .collect(Collectors.toList());
            int line = ctx.getStart().getLine();
            int col = ctx.getStart().getCharPositionInLine();
            return new FieldAccessNode(path, line, col);
        }

        @Override
        public ExpressionNode visitLiteral(RuleExpressionParser.LiteralContext ctx) {
            int line = ctx.getStart().getLine();
            int col = ctx.getStart().getCharPositionInLine();

            if (ctx.NUMBER() != null) {
                String numStr = ctx.NUMBER().getText();
                Object value;
                if (numStr.contains(".")) {
                    value = Double.parseDouble(numStr);
                } else {
                    try {
                        value = Long.parseLong(numStr);
                    } catch (NumberFormatException e) {
                        value = Double.parseDouble(numStr);
                    }
                }
                return new LiteralNode(LiteralNode.LiteralType.NUMBER, value, line, col);
            }

            if (ctx.STRING() != null) {
                String value = unquoteString(ctx.STRING().getText());
                return new LiteralNode(LiteralNode.LiteralType.STRING, value, line, col);
            }

            if (ctx.BOOLEAN() != null) {
                Boolean value = Boolean.parseBoolean(ctx.BOOLEAN().getText().toLowerCase());
                return new LiteralNode(LiteralNode.LiteralType.BOOLEAN, value, line, col);
            }

            if (ctx.NULL() != null) {
                return new LiteralNode(LiteralNode.LiteralType.NULL, null, line, col);
            }

            throw new RuleCompilationException("未知的字面量类型");
        }

        private BinaryOpNode.BinaryOperator mapComparisonOperator(
                RuleExpressionParser.ComparisonOperatorContext ctx) {
            String text = ctx.getText();
            return switch (text) {
                case ">" -> BinaryOpNode.BinaryOperator.GT;
                case ">=" -> BinaryOpNode.BinaryOperator.GTE;
                case "<" -> BinaryOpNode.BinaryOperator.LT;
                case "<=" -> BinaryOpNode.BinaryOperator.LTE;
                case "==", "=" -> BinaryOpNode.BinaryOperator.EQ;
                case "!=", "<>" -> BinaryOpNode.BinaryOperator.NEQ;
                default -> throw new RuleCompilationException("未知的比较运算符: " + text);
            };
        }

        private String unquoteString(String quoted) {
            if (quoted.length() < 2) {
                return quoted;
            }
            char quote = quoted.charAt(0);
            if ((quote == '"' || quote == '\'') && quoted.charAt(quoted.length() - 1) == quote) {
                String inner = quoted.substring(1, quoted.length() - 1);
                return unescapeString(inner);
            }
            return quoted;
        }

        private String unescapeString(String s) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                if (c == '\\' && i + 1 < s.length()) {
                    char next = s.charAt(i + 1);
                    switch (next) {
                        case '"' -> { sb.append('"'); i++; }
                        case '\'' -> { sb.append('\''); i++; }
                        case '\\' -> { sb.append('\\'); i++; }
                        case '/' -> { sb.append('/'); i++; }
                        case 'b' -> { sb.append('\b'); i++; }
                        case 'f' -> { sb.append('\f'); i++; }
                        case 'n' -> { sb.append('\n'); i++; }
                        case 'r' -> { sb.append('\r'); i++; }
                        case 't' -> { sb.append('\t'); i++; }
                        case 'u' -> {
                            if (i + 5 < s.length()) {
                                String hex = s.substring(i + 2, i + 6);
                                sb.append((char) Integer.parseInt(hex, 16));
                                i += 5;
                            }
                        }
                        default -> sb.append(c);
                    }
                } else {
                    sb.append(c);
                }
            }
            return sb.toString();
        }
    }

    /**
     * 静态分析Visitor：检查函数存在性、参数数量、类型兼容性等
     */
    private static class StaticAnalysisVisitor implements ExpressionNodeVisitor<Void> {
        private final List<String> errors = new ArrayList<>();

        public List<String> getErrors() {
            return errors;
        }

        @Override
        public Void visit(LiteralNode node) {
            return null;
        }

        @Override
        public Void visit(FieldAccessNode node) {
            if (node.getFieldPath().isEmpty()) {
                errors.add(formatError(node, "字段路径不能为空"));
                return null;
            }
            String root = node.getRootField();
            if (!RESERVED_FIELDS.contains(root)) {
                errors.add(formatError(node, "未知的根字段引用: " + root
                        + "，合法值为: " + RESERVED_FIELDS));
            }
            return null;
        }

        @Override
        public Void visit(BinaryOpNode node) {
            node.getLeft().accept(this);
            node.getRight().accept(this);
            return null;
        }

        @Override
        public Void visit(UnaryOpNode node) {
            node.getOperand().accept(this);
            return null;
        }

        @Override
        public Void visit(FunctionCallNode node) {
            String funcName = node.getFunctionName();
            if (!SUPPORTED_FUNCTIONS.contains(funcName)) {
                errors.add(formatError(node, "不支持的函数: " + funcName
                        + "，支持的函数: " + SUPPORTED_FUNCTIONS));
            }

            int[] range = FUNCTION_ARGUMENT_RANGES.get(funcName);
            if (range != null) {
                int argCount = node.getArgumentCount();
                int min = range[0];
                int max = range[1];
                if (argCount < min) {
                    errors.add(formatError(node,
                            String.format("函数 %s 至少需要 %d 个参数，实际 %d 个",
                                    funcName, min, argCount)));
                }
                if (argCount > max) {
                    errors.add(formatError(node,
                            String.format("函数 %s 最多接受 %d 个参数，实际 %d 个",
                                    funcName, max, argCount)));
                }
            }

            for (ExpressionNode arg : node.getArguments()) {
                arg.accept(this);
            }
            return null;
        }

        @Override
        public Void visit(InNode node) {
            node.getTarget().accept(this);
            for (ExpressionNode v : node.getValues()) {
                v.accept(this);
            }
            return null;
        }

        @Override
        public Void visit(BetweenNode node) {
            node.getTarget().accept(this);
            node.getLowerBound().accept(this);
            node.getUpperBound().accept(this);
            return null;
        }

        @Override
        public Void visit(LikeNode node) {
            node.getTarget().accept(this);
            return null;
        }

        @Override
        public Void visit(NullCheckNode node) {
            node.getTarget().accept(this);
            return null;
        }

        private String formatError(ExpressionNode node, String msg) {
            if (node.getLine() >= 0) {
                return String.format("[行%d, 列%d] %s", node.getLine(), node.getColumn(), msg);
            }
            return msg;
        }
    }
}
