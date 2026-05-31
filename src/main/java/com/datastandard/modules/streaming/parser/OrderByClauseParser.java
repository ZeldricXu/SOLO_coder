package com.datastandard.modules.streaming.parser;

import com.datastandard.modules.streaming.ast.SqlNode;
import com.datastandard.modules.streaming.common.SqlNodeBuilder;
import org.springframework.stereotype.Component;

@Component
public class OrderByClauseParser implements ClauseParser {

    @Override
    public SqlNode parse(String sql, int startIndex, int endIndex) {
        int orderByIdx = sql.toUpperCase().indexOf("ORDER BY", startIndex);
        if (orderByIdx == -1) {
            return null;
        }

        String orderByClause = sql.substring(orderByIdx + 8).trim();
        return SqlNodeBuilder.createOrderByNode(orderByClause);
    }

    @Override
    public String getClauseType() {
        return "ORDER_BY";
    }
}
