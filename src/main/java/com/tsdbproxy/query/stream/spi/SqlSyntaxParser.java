package com.tsdbproxy.query.stream.spi;

import com.tsdbproxy.query.stream.model.LogicalPlan;
import com.tsdbproxy.query.stream.model.QueryStatement;

public interface SqlSyntaxParser {

    LogicalPlan parse(QueryStatement statement);
}
