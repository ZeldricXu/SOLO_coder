package com.loganalytics.query;

import com.loganalytics.query.ast.ASTNode;
import com.loganalytics.query.ast.ASTNode.*;
import com.loganalytics.query.parser.LogQueryLexer;
import com.loganalytics.query.parser.LogQueryParser;
import org.antlr.v4.runtime.*;
import org.antlr.v4.runtime.tree.ParseTree;
import org.antlr.v4.runtime.tree.ParseTreeWalker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

public class QueryParser {
    private static final Logger log = LoggerFactory.getLogger(QueryParser.class);

    public static ParseResult parse(String query) {
        if (query == null || query.isBlank()) {
            return new ParseResult(null, List.of("Query cannot be empty"));
        }

        try {
            CharStream input = CharStreams.fromString(query);
            LogQueryLexer lexer = new LogQueryLexer(input);
            CommonTokenStream tokens = new CommonTokenStream(lexer);
            LogQueryParser parser = new LogQueryParser(tokens);

            List<String> errors = new ArrayList<>();
            lexer.removeErrorListeners();
            lexer.addErrorListener(new BaseErrorListener() {
                @Override
                public void syntaxError(Recognizer<?, ?> recognizer, Object offendingSymbol,
                                      int line, int charPositionInLine, String msg, RecognitionException e) {
                    errors.add(String.format("Line %d:%d: %s", line, charPositionInLine, msg));
                }
            });

            parser.removeErrorListeners();
            parser.addErrorListener(new BaseErrorListener() {
                @Override
                public void syntaxError(Recognizer<?, ?> recognizer, Object offendingSymbol,
                                      int line, int charPositionInLine, String msg, RecognitionException e) {
                    errors.add(String.format("Line %d:%d: %s", line, charPositionInLine, msg));
                }
            });

            ParseTree tree = parser.query();

            if (!errors.isEmpty()) {
                return new ParseResult(null, errors);
            }

            ASTBuilder astBuilder = new ASTBuilder();
            ParseTreeWalker walker = new ParseTreeWalker();
            walker.walk(astBuilder, tree);

            QueryNode queryNode = astBuilder.getQuery();
            return new ParseResult(queryNode, new ArrayList<>());

        } catch (Exception e) {
            log.error("Failed to parse query: {}", query, e);
            return new ParseResult(null, List.of("Parse error: " + e.getMessage()));
        }
    }

    private static class ASTBuilder extends com.loganalytics.query.parser.LogQueryBaseListener {
        private QueryNode query;
        private ExpressionNode expression;
        private TimeRangeNode timeRange;
        private final List<ExpressionNode> expressionStack = new ArrayList<>();

        public QueryNode getQuery() {
            return query;
        }

        @Override
        public void exitQuery(com.loganalytics.query.parser.LogQueryParser.QueryContext ctx) {
            query = new QueryNode(expression, timeRange);
        }

        @Override
        public void enterAndExpression(com.loganalytics.query.parser.LogQueryParser.AndExpressionContext ctx) {
            expressionStack.add(null);
        }

        @Override
        public void exitAndExpression(com.loganalytics.query.parser.LogQueryParser.AndExpressionContext ctx) {
            ExpressionNode right = expressionStack.remove(expressionStack.size() - 1);
            ExpressionNode left = expressionStack.remove(expressionStack.size() - 1);
            ExpressionNode andExpr = new AndExpression(left, right);
            if (expressionStack.isEmpty()) {
                expression = andExpr;
            } else {
                expressionStack.set(expressionStack.size() - 1, andExpr);
            }
        }

        @Override
        public void enterOrExpression(com.loganalytics.query.parser.LogQueryParser.OrExpressionContext ctx) {
            expressionStack.add(null);
        }

        @Override
        public void exitOrExpression(com.loganalytics.query.parser.LogQueryParser.OrExpressionContext ctx) {
            ExpressionNode right = expressionStack.remove(expressionStack.size() - 1);
            ExpressionNode left = expressionStack.remove(expressionStack.size() - 1);
            ExpressionNode orExpr = new OrExpression(left, right);
            if (expressionStack.isEmpty()) {
                expression = orExpr;
            } else {
                expressionStack.set(expressionStack.size() - 1, orExpr);
            }
        }

        @Override
        public void enterNotExpression(com.loganalytics.query.parser.LogQueryParser.NotExpressionContext ctx) {
            expressionStack.add(null);
        }

        @Override
        public void exitNotExpression(com.loganalytics.query.parser.LogQueryParser.NotExpressionContext ctx) {
            ExpressionNode inner = expressionStack.remove(expressionStack.size() - 1);
            ExpressionNode notExpr = new NotExpression(inner);
            if (expressionStack.isEmpty()) {
                expression = notExpr;
            } else {
                expressionStack.set(expressionStack.size() - 1, notExpr);
            }
        }

        @Override
        public void exitParenExpression(com.loganalytics.query.parser.LogQueryParser.ParenExpressionContext ctx) {
        }

        @Override
        public void exitExactMatch(com.loganalytics.query.parser.LogQueryParser.ExactMatchContext ctx) {
            String fieldName = ctx.fieldName().getText();
            String value = unquote(ctx.value().getText());
            ValueNode valueNode = new ValueNode(value);
            FieldComparisonNode node = new FieldComparisonNode(
                    fieldName, ComparisonType.EXACT, valueNode
            );
            if (expressionStack.isEmpty()) {
                expression = node;
            } else {
                expressionStack.set(expressionStack.size() - 1, node);
            }
        }

        @Override
        public void exitRegexMatch(com.loganalytics.query.parser.LogQueryParser.RegexMatchContext ctx) {
            String fieldName = ctx.fieldName().getText();
            String value = unquote(ctx.value().getText());
            ValueNode valueNode = new ValueNode(value);
            FieldComparisonNode node = new FieldComparisonNode(
                    fieldName, ComparisonType.REGEX, valueNode
            );
            if (expressionStack.isEmpty()) {
                expression = node;
            } else {
                expressionStack.set(expressionStack.size() - 1, node);
            }
        }

        @Override
        public void exitRangeMatch(com.loganalytics.query.parser.LogQueryParser.RangeMatchContext ctx) {
            String fieldName = ctx.fieldName().getText();
            List<com.loganalytics.query.parser.LogQueryParser.ValueContext> values = ctx.value();
            ValueNode start = new ValueNode(unquote(values.get(0).getText()));
            ValueNode end = new ValueNode(unquote(values.get(1).getText()));
            FieldComparisonNode node = new FieldComparisonNode(fieldName, start, end);
            if (expressionStack.isEmpty()) {
                expression = node;
            } else {
                expressionStack.set(expressionStack.size() - 1, node);
            }
        }

        @Override
        public void exitNumericCompare(com.loganalytics.query.parser.LogQueryParser.NumericCompareContext ctx) {
            String fieldName = ctx.fieldName().getText();
            String op = ctx.OP().getText();
            String value = ctx.value().getText();
            ValueNode valueNode;

            try {
                valueNode = new ValueNode(Double.parseDouble(value));
            } catch (NumberFormatException e) {
                valueNode = new ValueNode(unquote(value));
            }

            ComparisonType type = switch (op) {
                case ">" -> ComparisonType.GREATER_THAN;
                case "<" -> ComparisonType.LESS_THAN;
                case ">=" -> ComparisonType.GREATER_EQUAL;
                case "<=" -> ComparisonType.LESS_EQUAL;
                case "!=" -> ComparisonType.NOT_EQUAL;
                case "=" -> ComparisonType.EQUAL;
                default -> ComparisonType.EXACT;
            };

            FieldComparisonNode node = new FieldComparisonNode(fieldName, type, valueNode);
            if (expressionStack.isEmpty()) {
                expression = node;
            } else {
                expressionStack.set(expressionStack.size() - 1, node);
            }
        }

        @Override
        public void exitFulltextExpression(com.loganalytics.query.parser.LogQueryParser.FulltextExpressionContext ctx) {
            String text = unquote(ctx.getText());
            FulltextExpression node = new FulltextExpression(text);
            if (expressionStack.isEmpty()) {
                expression = node;
            } else {
                expressionStack.set(expressionStack.size() - 1, node);
            }
        }

        @Override
        public void exitTimeRange(com.loganalytics.query.parser.LogQueryParser.TimeRangeContext ctx) {
            if (ctx.SINCE() != null) {
                com.loganalytics.query.parser.LogQueryParser.DurationContext durCtx = ctx.duration();
                long millis = parseDuration(durCtx.NUMBER().getText(), durCtx.UNIT().getText());
                timeRange = new TimeRangeNode(millis);
            } else if (ctx.BETWEEN() != null) {
                List<com.loganalytics.query.parser.LogQueryParser.TimeInstantContext> times = ctx.timeInstant();
                Instant start = parseInstant(times.get(0).getText());
                Instant end = parseInstant(times.get(1).getText());
                timeRange = new TimeRangeNode(TimeRangeType.BETWEEN, start, end);
            } else {
                List<com.loganalytics.query.parser.LogQueryParser.TimeInstantContext> times = ctx.timeInstant();
                Instant start = parseInstant(times.get(0).getText());
                Instant end = parseInstant(times.get(1).getText());
                timeRange = new TimeRangeNode(TimeRangeType.TO, start, end);
            }
        }

        private String unquote(String s) {
            if (s == null) return null;
            if ((s.startsWith("\"") && s.endsWith("\"")) ||
                (s.startsWith("'") && s.endsWith("'"))) {
                return s.substring(1, s.length() - 1)
                        .replace("\\\"", "\"")
                        .replace("\\'", "'");
            }
            return s;
        }

        private long parseDuration(String number, String unit) {
            long value = Long.parseLong(number);
            return switch (unit) {
                case "s" -> value * 1000;
                case "m" -> value * 60 * 1000;
                case "h" -> value * 60 * 60 * 1000;
                case "d" -> value * 24 * 60 * 60 * 1000;
                case "w" -> value * 7 * 24 * 60 * 60 * 1000;
                default -> value * 1000;
            };
        }

        private Instant parseInstant(String text) {
            return switch (text.toLowerCase()) {
                case "now" -> Instant.now();
                case "today" -> LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant();
                case "yesterday" -> LocalDate.now().minusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant();
                case "this_week" -> LocalDate.now().with(java.time.DayOfWeek.MONDAY).atStartOfDay(ZoneId.systemDefault()).toInstant();
                case "last_week" -> LocalDate.now().minusWeeks(1).with(java.time.DayOfWeek.MONDAY).atStartOfDay(ZoneId.systemDefault()).toInstant();
                case "this_month" -> LocalDate.now().withDayOfMonth(1).atStartOfDay(ZoneId.systemDefault()).toInstant();
                case "last_month" -> LocalDate.now().minusMonths(1).withDayOfMonth(1).atStartOfDay(ZoneId.systemDefault()).toInstant();
                case "this_year" -> LocalDate.now().withDayOfYear(1).atStartOfDay(ZoneId.systemDefault()).toInstant();
                default -> {
                    try {
                        yield Instant.parse(text);
                    } catch (Exception e) {
                        try {
                            yield LocalDateTime.parse(text, DateTimeFormatter.ISO_LOCAL_DATE_TIME)
                                    .atZone(ZoneId.systemDefault()).toInstant();
                        } catch (Exception e2) {
                            try {
                                yield LocalDate.parse(text, DateTimeFormatter.ISO_LOCAL_DATE)
                                        .atStartOfDay(ZoneId.systemDefault()).toInstant();
                            } catch (Exception e3) {
                                log.warn("Could not parse date: {}", text);
                                yield Instant.now();
                            }
                        }
                    }
                }
            };
        }
    }
}
