package com.datastandard.modules.streaming.parser;

import com.datastandard.modules.streaming.ast.SqlNode;
import com.datastandard.modules.streaming.common.SqlNodeBuilder;
import org.springframework.stereotype.Component;

@Component
public class GroupByClauseParser implements ClauseParser {

    @Override
    public SqlNode parse(String sql, int startIndex, int endIndex) {
        int groupByIdx = sql.toUpperCase().indexOf("GROUP BY", startIndex);
        if (groupByIdx == -1) {
            return null;
        }

        int actualEnd = endIndex != -1 ? endIndex : sql.length();
        String groupByClause = sql.substring(groupByIdx + 8, actualEnd).trim();

        return SqlNodeBuilder.createGroupByNode(groupByClause);
    }

    @Override
    public String getClauseType() {
        return "GROUP_BY";
    }
}
