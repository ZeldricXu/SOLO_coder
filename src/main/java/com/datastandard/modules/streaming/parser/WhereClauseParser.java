package com.datastandard.modules.streaming.parser;

import com.datastandard.modules.streaming.ast.SqlNode;
import com.datastandard.modules.streaming.common.SqlNodeBuilder;
import org.springframework.stereotype.Component;

@Component
public class WhereClauseParser implements ClauseParser {

    @Override
    public SqlNode parse(String sql, int startIndex, int endIndex) {
        int whereIdx = sql.toUpperCase().indexOf("WHERE", startIndex);
        if (whereIdx == -1) {
            return null;
        }

        int actualEnd = endIndex != -1 ? endIndex : sql.length();
        String whereClause = sql.substring(whereIdx + 5, actualEnd).trim();

        return SqlNodeBuilder.createWhereNode(whereClause);
    }

    @Override
    public String getClauseType() {
        return "WHERE";
    }
}
