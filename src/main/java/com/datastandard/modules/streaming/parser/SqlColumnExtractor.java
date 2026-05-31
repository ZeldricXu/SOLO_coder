package com.datastandard.modules.streaming.parser;

import com.datastandard.modules.streaming.common.SqlParseUtils;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class SqlColumnExtractor {

    public List<String> extractColumns(String sql) {
        List<String> columns = new ArrayList<>();

        String upper = sql.trim().toUpperCase();
        int selectIdx = upper.indexOf("SELECT");
        int fromIdx = upper.indexOf("FROM");

        if (selectIdx == -1 || fromIdx == -1) {
            return columns;
        }

        String selectPart = sql.substring(selectIdx + 6, fromIdx).trim();
        String[] cols = selectPart.split(",");

        for (String col : cols) {
            columns.add(processColumn(col.trim()));
        }

        return columns;
    }

    private String processColumn(String column) {
        String upper = column.toUpperCase();
        if (hasAlias(column)) {
            return extractAlias(column);
        } else if (isFunctionCall(column)) {
            return column;
        } else {
            return column;
        }
    }

    private boolean hasAlias(String column) {
        return column.toUpperCase().matches(".*\\s+(AS)\\s+.*");
    }

    private String extractAlias(String column) {
        String[] parts = column.split("\\s+(?i)AS\\s+");
        return parts[parts.length - 1].trim();
    }

    private boolean isFunctionCall(String column) {
        return column.contains("(") && column.contains(")");
    }

    public String extractTableName(String sql) {
        FromClauseParser fromParser = new FromClauseParser();
        String upper = sql.trim().toUpperCase();
        int fromIdx = upper.indexOf("FROM");
        if (fromIdx == -1) return null;

        int whereIdx = upper.indexOf("WHERE");
        int joinIdx = upper.indexOf("JOIN");
        int groupByIdx = upper.indexOf("GROUP BY");

        int endIdx = SqlParseUtils.findEndIndex(sql, whereIdx, joinIdx, groupByIdx);
        if (endIdx == -1) {
            endIdx = sql.length();
        }

        String fromPart = sql.substring(fromIdx + 4, endIdx).trim();
        return fromParser.extractTableName(fromPart);
    }
}
