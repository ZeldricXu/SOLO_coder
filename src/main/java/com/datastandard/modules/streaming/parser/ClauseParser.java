package com.datastandard.modules.streaming.parser;

import com.datastandard.modules.streaming.ast.SqlNode;

public interface ClauseParser {
    SqlNode parse(String sql, int startIndex, int endIndex);
    String getClauseType();
}
