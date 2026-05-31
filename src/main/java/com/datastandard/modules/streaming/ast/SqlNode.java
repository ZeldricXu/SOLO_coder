package com.datastandard.modules.streaming.ast;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SqlNode {
    private NodeType type;
    private String value;
    @Builder.Default
    private List<SqlNode> children = new ArrayList<>();

    public enum NodeType {
        SELECT, FROM, WHERE, GROUP_BY, ORDER_BY, JOIN,
        PROJECTION, EXPRESSION, FUNCTION_CALL, IDENTIFIER,
        LITERAL, OPERATOR, WINDOW, TUMBLE, HOP, SESSION,
        UNION, INTERSECT, EXCEPT, SUBQUERY
    }
}
