package com.datastandard.modules.streaming.parser;

import com.datastandard.modules.streaming.common.SqlParseUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class SqlFormatter {

    public String format(String sql) {
        log.info("格式化SQL");
        String normalized = SqlParseUtils.normalizeSql(sql);
        StringBuilder formatted = new StringBuilder();

        String upper = normalized.toUpperCase();
        int[] positions = findClausePositions(normalized);

        appendSelectClause(formatted, normalized, upper, positions);
        appendFromClause(formatted, normalized, upper, positions);
        appendWhereClause(formatted, normalized, upper, positions);
        appendGroupByClause(formatted, normalized, upper, positions);
        appendOrderByClause(formatted, normalized, upper, positions);

        return formatted.toString();
    }

    private int[] findClausePositions(String sql) {
        String upper = sql.toUpperCase();
        return new int[] {
                upper.indexOf("SELECT"),
                upper.indexOf("FROM"),
                upper.indexOf("WHERE"),
                upper.indexOf("GROUP BY"),
                upper.indexOf("ORDER BY")
        };
    }

    private void appendSelectClause(StringBuilder formatted, String normalized, String upper, int[] positions) {
        if (positions[0] == -1) return;

        formatted.append("SELECT\n");
        int fromStart = positions[1] != -1 ? positions[1] : normalized.length();
        String selectPart = normalized.substring(positions[0] + 6, fromStart).trim();

        String[] cols = selectPart.split(",");
        for (int i = 0; i < cols.length; i++) {
            String prefix = i == 0 ? "    " : ",\n    ";
            formatted.append(prefix).append(cols[i].trim());
        }
        formatted.append("\n");
    }

    private void appendFromClause(StringBuilder formatted, String normalized, String upper, int[] positions) {
        if (positions[1] == -1) return;

        formatted.append("FROM\n");
        int whereStart = findNextValidPos(positions, 2);
        formatted.append("    ").append(normalized.substring(positions[1] + 4, whereStart).append("\n");
    }

    private void appendWhereClause(StringBuilder formatted, String normalized, String upper, int[] positions) {
        if (positions[2] == -1) return;

        formatted.append("WHERE\n");
        int groupByStart = findNextValidPos(positions, 3);
        formatted.append("    ").append(normalized.substring(positions[2] + 5, groupByStart).append("\n");
    }

    private void appendGroupByClause(StringBuilder formatted, String normalized, String upper, int[] positions) {
        if (positions[3] == -1) return;

        formatted.append("GROUP BY\n");
        int orderByStart = positions[4] != -1 ? positions[4] : normalized.length();
        formatted.append("    ").append(normalized.substring(positions[3] + 8, orderByStart).append("\n");
    }

    private void appendOrderByClause(StringBuilder formatted, String normalized, String upper, int[] positions) {
        if (positions[4] == -1) return;

        formatted.append("ORDER BY\n");
        formatted.append("    ").append(normalized.substring(positions[4] + 8);
    }

    private int findNextValidPos(int[] positions, int start) {
        for (int i = start; i < positions.length; i++) {
            if (positions[i] != -1) {
                return positions[i];
            }
        }
        return -1;
    }
}
