package com.datastandard.modules.streaming.parser;

import com.datastandard.modules.streaming.ast.SqlNode;
import com.datastandard.modules.streaming.common.SqlParseUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

@Slf4j
@Service
public class StreamingSqlParser {

    private final SelectClauseParser selectParser;
    private final FromClauseParser fromParser;
    private final WhereClauseParser whereParser;
    private final GroupByClauseParser groupByParser;
    private final OrderByClauseParser orderByParser;
    private final JoinClauseParser joinParser;
    private final SqlFormatter formatter;
    private final SqlValidator validator;
    private final SqlColumnExtractor columnExtractor;

    public StreamingSqlParser(SelectClauseParser selectParser,
                              FromClauseParser fromParser,
                              WhereClauseParser whereParser,
                              GroupByClauseParser groupByParser,
                              OrderByClauseParser orderByParser,
                              JoinClauseParser joinParser,
                              SqlFormatter formatter,
                              SqlValidator validator,
                              SqlColumnExtractor columnExtractor) {
        this.selectParser = selectParser;
        this.fromParser = fromParser;
        this.whereParser = whereParser;
        this.groupByParser = groupByParser;
        this.orderByParser = orderByParser;
        this.joinParser = joinParser;
        this.formatter = formatter;
        this.validator = validator;
        this.columnExtractor = columnExtractor;
    }

    public SqlNode parse(String sql) {
        log.info("开始解析流式SQL");

        if (!validator.validate(sql)) {
            throw new IllegalArgumentException("SQL语法验证失败");
        }

        String normalizedSql = SqlParseUtils.normalizeSql(sql);
        SqlNode root = SqlNode.builder()
                .type(SqlNode.NodeType.SELECT)
                .children(new java.util.ArrayList<>())
                .build();

        int[] positions = calculateClausePositions(normalizedSql);

        parseSelectClause(root, normalizedSql, positions);
        parseFromClause(root, normalizedSql, positions);
        parseJoinClause(root, normalizedSql, positions);
        parseWhereClause(root, normalizedSql, positions);
        parseGroupByClause(root, normalizedSql, positions);
        parseOrderByClause(root, normalizedSql, positions);

        log.info("SQL解析完成, 节点数: {}", countNodes(root));

        return root;
    }

    private int[] calculateClausePositions(String sql) {
        String upper = sql.toUpperCase();
        return new int[] {
                upper.indexOf("SELECT"),
                upper.indexOf("FROM"),
                upper.indexOf("WHERE"),
                upper.indexOf("GROUP BY"),
                upper.indexOf("ORDER BY"),
                upper.indexOf("JOIN"),
                upper.indexOf("WINDOW")
        };
    }

    private void parseSelectClause(SqlNode root, String sql, int[] positions) {
        int endIdx = SqlParseUtils.findEndIndex(sql, positions[1]);
        SqlNode selectNode = selectParser.parse(sql, positions[0], endIdx);
        if (selectNode != null) {
            root.getChildren().add(selectNode);
        }
    }

    private void parseFromClause(SqlNode root, String sql, int[] positions) {
        int endIdx = SqlParseUtils.findEndIndex(sql, positions[2], positions[5], positions[3], positions[6]);
        SqlNode fromNode = fromParser.parse(sql, positions[1], endIdx);
        if (fromNode != null) {
            root.getChildren().add(fromNode);
        }
    }

    private void parseJoinClause(SqlNode root, String sql, int[] positions) {
        int endIdx = SqlParseUtils.findEndIndex(sql, positions[2], positions[3], positions[6]);
        SqlNode joinNode = joinParser.parse(sql, positions[5], endIdx);
        if (joinNode != null) {
            root.getChildren().add(joinNode);
        }
    }

    private void parseWhereClause(SqlNode root, String sql, int[] positions) {
        int endIdx = SqlParseUtils.findEndIndex(sql, positions[3], positions[4], positions[6]);
        SqlNode whereNode = whereParser.parse(sql, positions[2], endIdx);
        if (whereNode != null) {
            root.getChildren().add(whereNode);
        }
    }

    private void parseGroupByClause(SqlNode root, String sql, int[] positions) {
        int endIdx = SqlParseUtils.findEndIndex(sql, positions[4], positions[6]);
        SqlNode groupByNode = groupByParser.parse(sql, positions[3], endIdx);
        if (groupByNode != null) {
            root.getChildren().add(groupByNode);
        }
    }

    private void parseOrderByClause(SqlNode root, String sql, int[] positions) {
        SqlNode orderByNode = orderByParser.parse(sql, positions[4], -1);
        if (orderByNode != null) {
            root.getChildren().add(orderByNode);
        }
    }

    private int countNodes(SqlNode node) {
        if (node == null) return 0;
        int count = 1;
        for (SqlNode child : node.getChildren()) {
            count += countNodes(child);
        }
        return count;
    }

    public String formatSql(String sql) {
        return formatter.format(sql);
    }

    public boolean validateSql(String sql) {
        return validator.validate(sql);
    }

    public String extractTableName(String sql) {
        return columnExtractor.extractTableName(sql);
    }

    public List<String> extractColumns(String sql) {
        return columnExtractor.extractColumns(sql);
    }
}
