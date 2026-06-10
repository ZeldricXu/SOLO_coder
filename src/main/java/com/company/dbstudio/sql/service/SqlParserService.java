package com.company.dbstudio.sql.service;

import com.company.dbstudio.core.model.Result;
import com.company.dbstudio.sql.model.ExecutionPlan;
import com.company.dbstudio.sql.model.IndexSuggestion;
import com.company.dbstudio.sql.model.SqlKeyword;
import com.company.dbstudio.sql.model.StatementAnalysis;
import net.sf.jsqlparser.JSQLParserException;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.operators.conditional.AndExpression;
import net.sf.jsqlparser.expression.operators.relational.EqualsTo;
import net.sf.jsqlparser.expression.operators.relational.GreaterThan;
import net.sf.jsqlparser.expression.operators.relational.LikeExpression;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.delete.Delete;
import net.sf.jsqlparser.statement.insert.Insert;
import net.sf.jsqlparser.statement.select.*;
import net.sf.jsqlparser.statement.update.Update;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class SqlParserService {
    private static final Pattern WORD_PATTERN = Pattern.compile("\\b\\w+\\b");
    private static final Pattern SELECT_PATTERN = Pattern.compile("(?i)\\bSELECT\\b");
    private static final Pattern FROM_PATTERN = Pattern.compile("(?i)\\bFROM\\b\\s+(\\w+)");
    private static final Pattern WHERE_PATTERN = Pattern.compile("(?i)\\bWHERE\\b");
    private static final Pattern JOIN_PATTERN = Pattern.compile("(?i)\\bJOIN\\b\\s+(\\w+)");
    private static final Pattern ORDER_BY_PATTERN = Pattern.compile("(?i)\\bORDER\\s+BY\\b\\s+(\\w+)");
    private static final Pattern GROUP_BY_PATTERN = Pattern.compile("(?i)\\bGROUP\\s+BY\\b\\s+(\\w+)");

    private static SqlParserService instance;

    private SqlParserService() {
    }

    public static synchronized SqlParserService getInstance() {
        if (instance == null) {
            instance = new SqlParserService();
        }
        return instance;
    }

    public Result<Statement> parse(String sql) {
        try {
            Statement stmt = CCJSqlParserUtil.parse(sql);
            return Result.success(stmt);
        } catch (JSQLParserException e) {
            return Result.failure("SQL解析失败: " + e.getMessage());
        }
    }

    public List<String> extractTableNames(String sql) {
        Set<String> tables = new LinkedHashSet<>();
        try {
            Statement stmt = CCJSqlParserUtil.parse(sql);
            if (stmt instanceof Select select) {
                extractTablesFromSelect(select, tables);
            } else if (stmt instanceof Insert insert) {
                tables.add(insert.getTable().getName());
            } else if (stmt instanceof Update update) {
                update.getTables().forEach(t -> tables.add(t.getName()));
            } else if (stmt instanceof Delete delete) {
                tables.add(delete.getTable().getName());
            }
        } catch (JSQLParserException e) {
            extractTableNamesByRegex(sql, tables);
        }
        return new ArrayList<>(tables);
    }

    private void extractTablesFromSelect(Select select, Set<String> tables) {
        SelectBody selectBody = select.getSelectBody();
        if (selectBody instanceof PlainSelect plainSelect) {
            if (plainSelect.getFromItem() != null) {
                extractFromItem(plainSelect.getFromItem(), tables);
            }
            if (plainSelect.getJoins() != null) {
                for (Join join : plainSelect.getJoins()) {
                    extractFromItem(join.getRightItem(), tables);
                }
            }
        } else if (selectBody instanceof SetOperationList setOpList) {
            for (SelectBody body : setOpList.getSelects()) {
                if (body instanceof PlainSelect ps) {
                    if (ps.getFromItem() != null) {
                        extractFromItem(ps.getFromItem(), tables);
                    }
                }
            }
        }
    }

    private void extractFromItem(FromItem fromItem, Set<String> tables) {
        if (fromItem instanceof Table table) {
            tables.add(table.getName());
        } else if (fromItem instanceof SubSelect subSelect) {
            extractTablesFromSelect(subSelect.getSelect(), tables);
        }
    }

    private void extractTableNamesByRegex(String sql, Set<String> tables) {
        Matcher fromMatcher = FROM_PATTERN.matcher(sql);
        while (fromMatcher.find()) {
            tables.add(fromMatcher.group(1));
        }
        Matcher joinMatcher = JOIN_PATTERN.matcher(sql);
        while (joinMatcher.find()) {
            tables.add(joinMatcher.group(1));
        }
    }

    public List<String> extractColumnNames(String sql) {
        Set<String> columns = new LinkedHashSet<>();
        try {
            Statement stmt = CCJSqlParserUtil.parse(sql);
            if (stmt instanceof Select select) {
                extractColumnsFromSelect(select, columns);
            } else if (stmt instanceof Update update) {
                update.getColumns().forEach(c -> columns.add(c.getColumnName()));
                if (update.getWhere() != null) {
                    extractColumnsFromExpression(update.getWhere(), columns);
                }
            } else if (stmt instanceof Delete delete) {
                if (delete.getWhere() != null) {
                    extractColumnsFromExpression(delete.getWhere(), columns);
                }
            }
        } catch (JSQLParserException e) {
            extractColumnNamesByRegex(sql, columns);
        }
        return new ArrayList<>(columns);
    }

    private void extractColumnsFromSelect(Select select, Set<String> columns) {
        SelectBody selectBody = select.getSelectBody();
        if (selectBody instanceof PlainSelect plainSelect) {
            for (SelectItem item : plainSelect.getSelectItems()) {
                if (item instanceof SelectExpressionItem exprItem) {
                    Expression expr = exprItem.getExpression();
                    if (expr instanceof Column column) {
                        columns.add(column.getColumnName());
                    }
                }
            }
            if (plainSelect.getWhere() != null) {
                extractColumnsFromExpression(plainSelect.getWhere(), columns);
            }
            if (plainSelect.getOrderByElements() != null) {
                for (OrderByElement orderBy : plainSelect.getOrderByElements()) {
                    Expression expr = orderBy.getExpression();
                    if (expr instanceof Column column) {
                        columns.add(column.getColumnName());
                    }
                }
            }
            if (plainSelect.getGroupBy() != null && plainSelect.getGroupBy().getGroupByExpressions() != null) {
                for (Expression expr : plainSelect.getGroupBy().getGroupByExpressions()) {
                    if (expr instanceof Column column) {
                        columns.add(column.getColumnName());
                    }
                }
            }
        }
    }

    private void extractColumnsFromExpression(Expression expr, Set<String> columns) {
        if (expr instanceof Column column) {
            columns.add(column.getColumnName());
        } else if (expr instanceof AndExpression andExpr) {
            extractColumnsFromExpression(andExpr.getLeftExpression(), columns);
            extractColumnsFromExpression(andExpr.getRightExpression(), columns);
        } else if (expr instanceof EqualsTo equalsTo) {
            extractColumnsFromExpression(equalsTo.getLeftExpression(), columns);
            extractColumnsFromExpression(equalsTo.getRightExpression(), columns);
        } else if (expr instanceof GreaterThan gt) {
            extractColumnsFromExpression(gt.getLeftExpression(), columns);
            extractColumnsFromExpression(gt.getRightExpression(), columns);
        } else if (expr instanceof LikeExpression like) {
            extractColumnsFromExpression(like.getLeftExpression(), columns);
        }
    }

    private void extractColumnNamesByRegex(String sql, Set<String> columns) {
        String selectPart = sql.replaceAll("(?i)\\bFROM\\b.*", "");
        Matcher wordMatcher = WORD_PATTERN.matcher(selectPart);
        Set<String> keywords = SqlKeyword.getAllKeywordStrings().stream()
                .map(String::toUpperCase)
                .collect(Collectors.toSet());
        while (wordMatcher.find()) {
            String word = wordMatcher.group();
            if (!keywords.contains(word.toUpperCase()) && !word.matches("\\d+")) {
                columns.add(word);
            }
        }
    }

    public String determineQueryType(String sql) {
        String trimmed = sql.trim().toUpperCase();
        if (trimmed.startsWith("SELECT")) return "SELECT";
        if (trimmed.startsWith("INSERT")) return "INSERT";
        if (trimmed.startsWith("UPDATE")) return "UPDATE";
        if (trimmed.startsWith("DELETE")) return "DELETE";
        if (trimmed.startsWith("CREATE")) return "CREATE";
        if (trimmed.startsWith("ALTER")) return "ALTER";
        if (trimmed.startsWith("DROP")) return "DROP";
        if (trimmed.startsWith("EXPLAIN")) return "EXPLAIN";
        if (trimmed.startsWith("SHOW")) return "SHOW";
        if (trimmed.startsWith("USE")) return "USE";
        if (trimmed.startsWith("SET")) return "SET";
        return "UNKNOWN";
    }

    public boolean isReadOnlyQuery(String sql) {
        String type = determineQueryType(sql);
        return "SELECT".equals(type) || "SHOW".equals(type) || "EXPLAIN".equals(type);
    }

    public List<String> formatSql(String sql) {
        List<String> statements = new ArrayList<>();
        String[] parts = sql.split(";");
        for (String part : parts) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                statements.add(formatSingleStatement(trimmed));
            }
        }
        return statements;
    }

    private String formatSingleStatement(String sql) {
        StringBuilder result = new StringBuilder();
        String upperSql = sql.toUpperCase();
        int indent = 0;
        int i = 0;

        while (i < sql.length()) {
            boolean matched = false;
            for (SqlKeyword keyword : SqlKeyword.getAllKeywords()) {
                String kw = keyword.getKeyword();
                if (i + kw.length() <= sql.length() 
                        && upperSql.startsWith(kw, i) 
                        && (i == 0 || !Character.isLetterOrDigit(sql.charAt(i - 1)))
                        && (i + kw.length() >= sql.length() || !Character.isLetterOrDigit(sql.charAt(i + kw.length())))) {
                    
                    if (isTopLevelKeyword(keyword)) {
                        if (result.length() > 0 && result.charAt(result.length() - 1) != '\n') {
                            result.append("\n");
                        }
                        result.append(" ".repeat(indent * 4));
                    }
                    result.append(kw.toUpperCase());
                    i += kw.length();
                    matched = true;
                    break;
                }
            }
            if (!matched) {
                result.append(sql.charAt(i));
                i++;
            }
        }
        return result.toString();
    }

    private boolean isTopLevelKeyword(SqlKeyword keyword) {
        String kw = keyword.getKeyword().toUpperCase();
        return kw.equals("SELECT") || kw.equals("FROM") || kw.equals("WHERE") 
                || kw.equals("AND") || kw.equals("OR") || kw.equals("ORDER")
                || kw.equals("GROUP") || kw.equals("HAVING") || kw.equals("JOIN")
                || kw.equals("LEFT") || kw.equals("RIGHT") || kw.equals("INNER")
                || kw.equals("UNION") || kw.equals("INSERT") || kw.equals("UPDATE")
                || kw.equals("DELETE") || kw.equals("SET") || kw.equals("VALUES");
    }

    public List<String> splitStatements(String sql) {
        List<String> statements = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inSingleQuote = false;
        boolean inDoubleQuote = false;
        boolean inComment = false;
        boolean inLineComment = false;

        for (int i = 0; i < sql.length(); i++) {
            char c = sql.charAt(i);
            char next = i < sql.length() - 1 ? sql.charAt(i + 1) : '\0';

            if (inLineComment) {
                if (c == '\n') {
                    inLineComment = false;
                }
                current.append(c);
                continue;
            }

            if (inComment) {
                if (c == '*' && next == '/') {
                    inComment = false;
                    current.append(c);
                    current.append(next);
                    i++;
                } else {
                    current.append(c);
                }
                continue;
            }

            if (c == '-' && next == '-') {
                inLineComment = true;
                current.append(c);
                continue;
            }

            if (c == '/' && next == '*') {
                inComment = true;
                current.append(c);
                current.append(next);
                i++;
                continue;
            }

            if (c == '\'' && !inDoubleQuote) {
                inSingleQuote = !inSingleQuote;
            } else if (c == '"' && !inSingleQuote) {
                inDoubleQuote = !inDoubleQuote;
            }

            if (c == ';' && !inSingleQuote && !inDoubleQuote) {
                String stmt = current.toString().trim();
                if (!stmt.isEmpty()) {
                    statements.add(stmt);
                }
                current.setLength(0);
            } else {
                current.append(c);
            }
        }

        String lastStmt = current.toString().trim();
        if (!lastStmt.isEmpty()) {
            statements.add(lastStmt);
        }
        return statements;
    }

    private static final Set<String> DDL_KEYWORDS = new HashSet<>(Arrays.asList(
            "CREATE", "ALTER", "DROP", "TRUNCATE", "RENAME", "COMMENT"
    ));

    private static final Set<String> IMPLICIT_COMMIT_KEYWORDS = new HashSet<>(Arrays.asList(
            "CREATE DATABASE", "ALTER DATABASE", "DROP DATABASE",
            "CREATE TABLE", "ALTER TABLE", "DROP TABLE", "TRUNCATE TABLE", "RENAME TABLE",
            "CREATE INDEX", "ALTER INDEX", "DROP INDEX",
            "CREATE VIEW", "ALTER VIEW", "DROP VIEW",
            "CREATE USER", "ALTER USER", "DROP USER",
            "CREATE ROLE", "ALTER ROLE", "DROP ROLE",
            "CREATE SCHEMA", "ALTER SCHEMA", "DROP SCHEMA",
            "CREATE PROCEDURE", "ALTER PROCEDURE", "DROP PROCEDURE",
            "CREATE FUNCTION", "ALTER FUNCTION", "DROP FUNCTION",
            "CREATE TRIGGER", "ALTER TRIGGER", "DROP TRIGGER",
            "GRANT", "REVOKE",
            "LOCK TABLES", "UNLOCK TABLES",
            "BEGIN", "COMMIT", "ROLLBACK",
            "START TRANSACTION"
    ));

    public StatementAnalysis analyzeStatement(String sql) {
        String trimmed = sql.trim();
        String upperSql = trimmed.toUpperCase();
        String statementType = determineQueryType(sql);
        boolean isDDL = DDL_KEYWORDS.contains(statementType);

        boolean causesImplicitCommit = false;
        String description = "";

        for (String keyword : IMPLICIT_COMMIT_KEYWORDS) {
            if (upperSql.startsWith(keyword) || upperSql.contains(keyword + " ")) {
                causesImplicitCommit = true;
                description = "语句 \"" + keyword + "\" 会导致隐式提交当前事务";
                break;
            }
        }

        if ("CREATE".equals(statementType) && upperSql.contains("TEMPORARY")) {
            causesImplicitCommit = false;
            description = "CREATE TEMPORARY TABLE 不会导致隐式提交";
        }

        if ("SET".equals(statementType)) {
            causesImplicitCommit = false;
            description = "SET 语句不会导致隐式提交";
        }

        StatementAnalysis analysis = new StatementAnalysis(sql, statementType, isDDL, 
                causesImplicitCommit, description);

        if (causesImplicitCommit) {
            analysis.addWarning("⚠️ 此语句会隐式提交当前事务，无法回滚");
        }

        if (isDDL && !causesImplicitCommit) {
            analysis.addWarning("ℹ️ 此为DDL语句，执行后无法回滚");
        }

        return analysis;
    }

    public List<StatementAnalysis> analyzeStatements(List<String> statements) {
        List<StatementAnalysis> analyses = new ArrayList<>();
        for (String stmt : statements) {
            analyses.add(analyzeStatement(stmt));
        }
        return analyses;
    }

    public boolean hasImplicitCommitStatements(List<String> statements) {
        for (String stmt : statements) {
            StatementAnalysis analysis = analyzeStatement(stmt);
            if (analysis.causesImplicitCommit()) {
                return true;
            }
        }
        return false;
    }

    public List<String> getImplicitCommitWarnings(List<String> statements) {
        List<String> warnings = new ArrayList<>();
        for (int i = 0; i < statements.size(); i++) {
            StatementAnalysis analysis = analyzeStatement(statements.get(i));
            if (analysis.causesImplicitCommit()) {
                warnings.add("语句 " + (i + 1) + ": " + analysis.getDescription());
            }
        }
        return warnings;
    }

    public List<IndexSuggestion> analyzeForIndexSuggestions(String sql, ExecutionPlan plan) {
        List<IndexSuggestion> suggestions = new ArrayList<>();
        List<String> tables = extractTableNames(sql);
        Set<String> whereColumns = extractWhereColumns(sql);
        Set<String> joinColumns = extractJoinColumns(sql);
        Set<String> orderByColumns = extractOrderByColumns(sql);
        Set<String> groupByColumns = extractGroupByColumns(sql);

        if (plan != null) {
            for (ExecutionPlan node : plan.getAllNodes()) {
                if (node.isFullTableScan() && node.getObjectName() != null) {
                    String table = node.getObjectName();
                    List<String> suggestedColumns = new ArrayList<>();
                    
                    for (String col : whereColumns) {
                        suggestedColumns.add(col);
                    }
                    for (String col : joinColumns) {
                        if (!suggestedColumns.contains(col)) {
                            suggestedColumns.add(col);
                        }
                    }
                    
                    if (!suggestedColumns.isEmpty()) {
                        suggestions.add(new IndexSuggestion(
                                table,
                                suggestedColumns,
                                "INDEX",
                                "检测到全表扫描，建议在过滤条件列上创建索引"
                        ));
                    }
                }
                
                if (node.isSortOperation() && node.getRows() > 1000) {
                    for (String table : tables) {
                        if (!orderByColumns.isEmpty()) {
                            List<String> combinedCols = new ArrayList<>(orderByColumns);
                            for (String col : whereColumns) {
                                if (!combinedCols.contains(col)) {
                                    combinedCols.add(0, col);
                                }
                            }
                            suggestions.add(new IndexSuggestion(
                                    table,
                                    combinedCols,
                                    "INDEX",
                                    "检测到大量数据排序，建议在排序列上创建索引以避免文件排序"
                            ));
                        }
                    }
                }
            }
        } else {
            for (String table : tables) {
                List<String> suggestedCols = new ArrayList<>();
                for (String col : whereColumns) {
                    if (!suggestedCols.contains(col)) {
                        suggestedCols.add(col);
                    }
                }
                for (String col : joinColumns) {
                    if (!suggestedCols.contains(col)) {
                        suggestedCols.add(col);
                    }
                }
                
                if (!suggestedCols.isEmpty() && suggestedCols.size() <= 5) {
                    suggestions.add(new IndexSuggestion(
                            table,
                            suggestedCols,
                            "INDEX",
                            "基于查询条件和连接条件推荐的组合索引"
                    ));
                }
                
                if (!groupByColumns.isEmpty()) {
                    List<String> groupCols = new ArrayList<>(groupByColumns);
                    suggestions.add(new IndexSuggestion(
                            table,
                            groupCols,
                            "INDEX",
                            "基于GROUP BY列推荐的索引，可以优化分组聚合性能"
                    ));
                }
            }
        }
        return suggestions;
    }

    private Set<String> extractWhereColumns(String sql) {
        Set<String> columns = new LinkedHashSet<>();
        try {
            Statement stmt = CCJSqlParserUtil.parse(sql);
            if (stmt instanceof Select select) {
                SelectBody body = select.getSelectBody();
                if (body instanceof PlainSelect ps && ps.getWhere() != null) {
                    extractColumnsFromExpression(ps.getWhere(), columns);
                }
            } else if (stmt instanceof Update update && update.getWhere() != null) {
                extractColumnsFromExpression(update.getWhere(), columns);
            } else if (stmt instanceof Delete delete && delete.getWhere() != null) {
                extractColumnsFromExpression(delete.getWhere(), columns);
            }
        } catch (JSQLParserException e) {
            extractByPattern(sql, WHERE_PATTERN, columns);
        }
        return columns;
    }

    private Set<String> extractJoinColumns(String sql) {
        Set<String> columns = new LinkedHashSet<>();
        try {
            Statement stmt = CCJSqlParserUtil.parse(sql);
            if (stmt instanceof Select select) {
                SelectBody body = select.getSelectBody();
                if (body instanceof PlainSelect ps && ps.getJoins() != null) {
                    for (Join join : ps.getJoins()) {
                        if (join.getOnExpression() != null) {
                            extractColumnsFromExpression(join.getOnExpression(), columns);
                        }
                    }
                }
            }
        } catch (JSQLParserException ignored) {
        }
        return columns;
    }

    private Set<String> extractOrderByColumns(String sql) {
        Set<String> columns = new LinkedHashSet<>();
        extractByPattern(sql, ORDER_BY_PATTERN, columns);
        return columns;
    }

    private Set<String> extractGroupByColumns(String sql) {
        Set<String> columns = new LinkedHashSet<>();
        extractByPattern(sql, GROUP_BY_PATTERN, columns);
        return columns;
    }

    private void extractByPattern(String sql, Pattern pattern, Set<String> columns) {
        Matcher matcher = pattern.matcher(sql);
        if (matcher.find()) {
            columns.add(matcher.group(1));
        }
    }

    public String getCurrentWord(String text, int caretPosition) {
        if (caretPosition < 0 || caretPosition > text.length()) {
            return "";
        }
        int start = caretPosition;
        while (start > 0 && (Character.isLetterOrDigit(text.charAt(start - 1)) || text.charAt(start - 1) == '_')) {
            start--;
        }
        return text.substring(start, caretPosition);
    }

    public List<String> getCompletions(String currentWord, List<String> tableNames, List<String> columnNames) {
        List<String> completions = new ArrayList<>();
        String lowerWord = currentWord.toLowerCase();

        for (SqlKeyword keyword : SqlKeyword.getAllKeywords()) {
            if (keyword.getKeyword().toLowerCase().startsWith(lowerWord)) {
                completions.add(keyword.getKeyword());
            }
        }

        for (String table : tableNames) {
            if (table.toLowerCase().startsWith(lowerWord)) {
                completions.add(table);
            }
        }

        for (String column : columnNames) {
            if (column.toLowerCase().startsWith(lowerWord)) {
                completions.add(column);
            }
        }

        return completions;
    }
}
