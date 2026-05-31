package com.datastandard.modules.streaming.parser;

import com.datastandard.modules.streaming.ast.SqlNode;
import com.datastandard.modules.streaming.common.SqlNodeBuilder;
import org.springframework.stereotype.Component;

@Component
public class JoinClauseParser implements ClauseParser {

    @Override
    public SqlNode parse(String sql, int startIndex, int endIndex) {
        int joinIdx = sql.toUpperCase().indexOf("JOIN", startIndex);
        if (joinIdx == -1) {
            return null;
        }

        SqlNode joinNode = SqlNodeBuilder.createNode(SqlNode.NodeType.JOIN);

        int onIdx = sql.toUpperCase().indexOf("ON", joinIdx);
        if (onIdx != -1) {
            int actualEnd = endIndex != -1 ? endIndex : sql.length();
            joinNode.setValue(sql.substring(joinIdx, actualEnd).trim());
        }

        return joinNode;
    }

    @Override
    public String getClauseType() {
        return "JOIN";
    }
}
