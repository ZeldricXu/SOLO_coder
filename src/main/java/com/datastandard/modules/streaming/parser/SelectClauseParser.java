package com.datastandard.modules.streaming.parser;

import com.datastandard.modules.streaming.ast.SqlNode;
import com.datastandard.modules.streaming.common.SqlNodeBuilder;
import com.datastandard.modules.streaming.common.SqlParseUtils;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class SelectClauseParser implements ClauseParser {

    @Override
    public SqlNode parse(String sql, int startIndex, int endIndex) {
        int selectIdx = sql.toUpperCase().indexOf("SELECT", startIndex);
        if (selectIdx == -1) {
            return null;
        }

        int actualEnd = endIndex != -1 ? endIndex : sql.length();
        String selectClause = sql.substring(selectIdx + 6, actualEnd).trim();

        List<String> columns = List.of(SqlParseUtils.splitByComma(selectClause));
        return SqlNodeBuilder.createProjectionNode(columns);
    }

    @Override
    public String getClauseType() {
        return "SELECT";
    }
}
