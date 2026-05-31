package com.datastandard.modules.streaming.parser;

import com.datastandard.modules.streaming.ast.SqlNode;
import com.datastandard.modules.streaming.common.SqlNodeBuilder;
import com.datastandard.modules.streaming.common.SqlParseUtils;
import org.springframework.stereotype.Component;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class FromClauseParser implements ClauseParser {

    private static final Pattern TUMBLE_PATTERN = Pattern.compile("TUMBLE\\s*\\(([^,]+),\\s*([^)]+)\\)", Pattern.CASE_INSENSITIVE);
    private static final Pattern HOP_PATTERN = Pattern.compile("HOP\\s*\\(([^,]+),\\s*([^,]+),\\s*([^)]+)\\)", Pattern.CASE_INSENSITIVE);
    private static final Pattern SESSION_PATTERN = Pattern.compile("SESSION\\s*\\(([^,]+),\\s*([^)]+)\\)", Pattern.CASE_INSENSITIVE);

    @Override
    public SqlNode parse(String sql, int startIndex, int endIndex) {
        int fromIdx = sql.toUpperCase().indexOf("FROM", startIndex);
        if (fromIdx == -1) {
            return null;
        }

        int actualEnd = endIndex != -1 ? endIndex : sql.length();
        String fromClause = sql.substring(fromIdx + 4, actualEnd).trim();

        SqlNode fromNode = SqlNodeBuilder.createNode(SqlNode.NodeType.FROM);

        if (fromClause.toUpperCase().contains("TUMBLE")) {
            parseTumbleWindow(fromClause, fromNode);
        } else if (fromClause.toUpperCase().contains("HOP")) {
            parseHopWindow(fromClause, fromNode);
        } else if (fromClause.toUpperCase().contains("SESSION")) {
            parseSessionWindow(fromClause, fromNode);
        } else {
            SqlNode tableNode = SqlNodeBuilder.createNode(SqlNode.NodeType.IDENTIFIER, fromClause);
            fromNode.getChildren().add(tableNode);
        }

        return fromNode;
    }

    private void parseTumbleWindow(String fromClause, SqlNode fromNode) {
        Matcher matcher = TUMBLE_PATTERN.matcher(fromClause);
        if (matcher.find()) {
            String table = matcher.group(1).trim();
            String interval = matcher.group(2).trim();
            SqlNode windowNode = SqlNodeBuilder.createNode(SqlNode.NodeType.TUMBLE,
                    table + " WITH INTERVAL " + interval);
            fromNode.getChildren().add(windowNode);
        }
    }

    private void parseHopWindow(String fromClause, SqlNode fromNode) {
        Matcher matcher = HOP_PATTERN.matcher(fromClause);
        if (matcher.find()) {
            String table = matcher.group(1).trim();
            String slide = matcher.group(2).trim();
            String size = matcher.group(3).trim();
            SqlNode windowNode = SqlNodeBuilder.createNode(SqlNode.NodeType.HOP,
                    table + " WITH SLIDE " + slide + ", SIZE " + size);
            fromNode.getChildren().add(windowNode);
        }
    }

    private void parseSessionWindow(String fromClause, SqlNode fromNode) {
        Matcher matcher = SESSION_PATTERN.matcher(fromClause);
        if (matcher.find()) {
            String table = matcher.group(1).trim();
            String gap = matcher.group(2).trim();
            SqlNode windowNode = SqlNodeBuilder.createNode(SqlNode.NodeType.SESSION,
                    table + " WITH GAP " + gap);
            fromNode.getChildren().add(windowNode);
        }
    }

    @Override
    public String getClauseType() {
        return "FROM";
    }

    public String extractTableName(String fromClause) {
        if (fromClause.toUpperCase().contains("TUMBLE") ||
            fromClause.toUpperCase().contains("HOP") ||
            fromClause.toUpperCase().contains("SESSION")) {
            Pattern pattern = Pattern.compile("\\(([^,]+)", Pattern.CASE_INSENSITIVE);
            Matcher matcher = pattern.matcher(fromClause);
            if (matcher.find()) {
                return matcher.group(1).trim();
            }
        }
        return SqlParseUtils.extractFirstToken(fromClause);
    }
}
