package com.datastandard.modules.streaming.common;

import com.datastandard.modules.streaming.ast.SqlNode;

import java.util.ArrayList;
import java.util.List;

public class SqlNodeBuilder {

    private SqlNodeBuilder() {}

    public static SqlNode createNode(SqlNode.NodeType type) {
        SqlNode node = new SqlNode();
        node.setType(type);
        node.setChildren(new ArrayList<>());
        return node;
    }

    public static SqlNode createNode(SqlNode.NodeType type, String value) {
        SqlNode node = createNode(type);
        node.setValue(value);
        return node;
    }

    public static SqlNode createProjectionNode(List<String> columns) {
        SqlNode node = createNode(SqlNode.NodeType.PROJECTION);
        for (String col : columns) {
            SqlNode child = classifyColumn(col);
            node.getChildren().add(child);
        }
        return node;
    }

    public static SqlNode classifyColumn(String column) {
        String upper = column.toUpperCase();
        if (isFunctionCall(column)) {
            return createNode(SqlNode.NodeType.FUNCTION_CALL, column);
        } else if (hasAlias(column)) {
            return createNode(SqlNode.NodeType.IDENTIFIER, column);
        } else {
            return createNode(SqlNode.NodeType.IDENTIFIER, column);
        }
    }

    public static boolean isFunctionCall(String str) {
        String upper = str.toUpperCase();
        return (upper.contains("SUM(") ||
                upper.contains("AVG(") ||
                upper.contains("COUNT(") ||
                upper.contains("MIN(") ||
                upper.contains("MAX(") ||
                (str.contains("(") && str.contains(")")));
    }

    public static boolean hasAlias(String str) {
        return str.toUpperCase().contains(" AS ");
    }

    public static SqlNode createWhereNode(String condition) {
        SqlNode node = createNode(SqlNode.NodeType.WHERE);
        node.setValue(condition);

        String[] conditions = condition.split("\\s+(AND|OR)\\s+");
        for (String cond : conditions) {
            SqlNode exprNode = createNode(SqlNode.NodeType.EXPRESSION, cond.trim());
            node.getChildren().add(exprNode);
        }
        return node;
    }

    public static SqlNode createGroupByNode(String groupByClause) {
        SqlNode node = createNode(SqlNode.NodeType.GROUP_BY);
        node.setValue(groupByClause);

        String[] groups = SqlParseUtils.splitByComma(groupByClause);
        for (String group : groups) {
            SqlNode groupNode = createNode(SqlNode.NodeType.IDENTIFIER, group);
            node.getChildren().add(groupNode);
        }
        return node;
    }

    public static SqlNode createOrderByNode(String orderByClause) {
        SqlNode node = createNode(SqlNode.NodeType.ORDER_BY);
        node.setValue(orderByClause);

        String[] orders = SqlParseUtils.splitByComma(orderByClause);
        for (String order : orders) {
            SqlNode orderNode = createNode(SqlNode.NodeType.IDENTIFIER, order);
            node.getChildren().add(orderNode);
        }
        return node;
    }
}
