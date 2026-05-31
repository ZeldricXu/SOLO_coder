package com.cdcsync.lineage.core;

import net.sf.jsqlparser.expression.*;
import net.sf.jsqlparser.expression.operators.conditional.AndExpression;
import net.sf.jsqlparser.expression.operators.conditional.OrExpression;
import net.sf.jsqlparser.expression.operators.relational.*;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.create.table.CreateTable;
import net.sf.jsqlparser.statement.delete.Delete;
import net.sf.jsqlparser.statement.insert.Insert;
import net.sf.jsqlparser.statement.merge.Merge;
import net.sf.jsqlparser.statement.select.*;
import net.sf.jsqlparser.statement.update.Update;
import net.sf.jsqlparser.statement.upsert.Upsert;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class ColumnLineageExtractor {

    public List<LineageRelation> extractFromSelect(Select select) {
        List<LineageRelation> relations = new ArrayList<>();
        SelectBody selectBody = select.getSelectBody();

        if (selectBody instanceof PlainSelect plainSelect) {
            extractFromPlainSelect(plainSelect, relations, null);
        }

        return relations;
    }

    public List<LineageRelation> extractFromInsert(Insert insert) {
        List<LineageRelation> relations = new ArrayList<>();
        Table targetTable = insert.getTable();
        String targetTableName = extractTableName(targetTable);

        List<Column> targetColumns = insert.getColumns();
        Select select = insert.getSelect();

        if (select != null && targetColumns != null) {
            Map<String, String> columnAliasMap = new ConcurrentHashMap<>();
            List<LineageNode> sourceNodes = new ArrayList<>();

            if (select.getSelectBody() instanceof PlainSelect plainSelect) {
                extractSelectColumns(plainSelect, sourceNodes, columnAliasMap);
            }

            for (int i = 0; i < Math.min(targetColumns.size(), sourceNodes.size()); i++) {
                Column targetCol = targetColumns.get(i);
                LineageNode sourceNode = sourceNodes.get(i);

                LineageRelation relation = LineageRelation.builder()
                        .source(sourceNode)
                        .target(LineageNode.of(targetTableName, targetCol.getColumnName()))
                        .transformation("INSERT")
                        .transformationType("INSERT")
                        .build();
                relations.add(relation);
            }
        }

        return relations;
    }

    public List<LineageRelation> extractFromUpdate(Update update) {
        List<LineageRelation> relations = new ArrayList<>();
        Table targetTable = update.getTable();
        String targetTableName = extractTableName(targetTable);

        List<Column> targetColumns = update.getColumns();
        List<Expression> expressions = update.getExpressions();

        if (targetColumns != null && expressions != null) {
            for (int i = 0; i < targetColumns.size(); i++) {
                Column targetCol = targetColumns.get(i);
                Expression expr = expressions.get(i);

                Set<LineageNode> sourceNodes = new HashSet<>();
                extractColumnsFromExpression(expr, sourceNodes, targetTableName);

                for (LineageNode sourceNode : sourceNodes) {
                    LineageRelation relation = LineageRelation.builder()
                            .source(sourceNode)
                            .target(LineageNode.of(targetTableName, targetCol.getColumnName()))
                            .transformation(expr.toString())
                            .transformationType("UPDATE")
                            .build();
                    relations.add(relation);
                }
            }
        }

        return relations;
    }

    public List<LineageRelation> extractFromDelete(Delete delete) {
        List<LineageRelation> relations = new ArrayList<>();
        Table targetTable = delete.getTable();
        String targetTableName = extractTableName(targetTable);

        Expression where = delete.getWhere();
        if (where != null) {
            Set<LineageNode> sourceNodes = new HashSet<>();
            extractColumnsFromExpression(where, sourceNodes, targetTableName);

            for (LineageNode sourceNode : sourceNodes) {
                LineageRelation relation = LineageRelation.builder()
                        .source(sourceNode)
                        .target(LineageNode.of(targetTableName, "*"))
                        .transformation("DELETE WHERE: " + where)
                        .transformationType("DELETE")
                        .build();
                relations.add(relation);
            }
        }

        return relations;
    }

    public List<LineageRelation> extractFromCreateTable(CreateTable createTable) {
        List<LineageRelation> relations = new ArrayList<>();
        Table targetTable = createTable.getTable();
        String targetTableName = extractTableName(targetTable);

        Select select = createTable.getSelect();
        if (select != null && select.getSelectBody() instanceof PlainSelect plainSelect) {
            List<LineageNode> sourceNodes = new ArrayList<>();
            Map<String, String> columnAliasMap = new ConcurrentHashMap<>();
            extractSelectColumns(plainSelect, sourceNodes, columnAliasMap);

            for (LineageNode sourceNode : sourceNodes) {
                String targetColName = columnAliasMap.getOrDefault(sourceNode.getColumnName(), sourceNode.getColumnName());
                LineageRelation relation = LineageRelation.builder()
                        .source(sourceNode)
                        .target(LineageNode.of(targetTableName, targetColName))
                        .transformation("CREATE TABLE AS SELECT")
                        .transformationType("CTAS")
                        .build();
                relations.add(relation);
            }
        }

        return relations;
    }

    public List<LineageRelation> extractFromMerge(Merge merge) {
        return new ArrayList<>();
    }

    public List<LineageRelation> extractFromUpsert(Upsert upsert) {
        return new ArrayList<>();
    }

    private void extractFromPlainSelect(PlainSelect plainSelect, List<LineageRelation> relations, String targetTable) {
        List<LineageNode> sourceNodes = new ArrayList<>();
        Map<String, String> columnAliasMap = new ConcurrentHashMap<>();
        extractSelectColumns(plainSelect, sourceNodes, columnAliasMap);

        for (LineageNode node : sourceNodes) {
            String alias = columnAliasMap.get(node.getColumnName());
            if (alias != null) {
                LineageRelation relation = LineageRelation.builder()
                        .source(node)
                        .target(LineageNode.of(targetTable != null ? targetTable : "RESULT", alias))
                        .transformation("SELECT")
                        .transformationType("SELECT")
                        .build();
                relations.add(relation);
            }
        }
    }

    private void extractSelectColumns(PlainSelect plainSelect, List<LineageNode> nodes, Map<String, String> aliasMap) {
        List<SelectItem<?>> selectItems = plainSelect.getSelectItems();
        Map<String, String> tableAliasMap = new ConcurrentHashMap<>();

        FromItem fromItem = plainSelect.getFromItem();
        extractTableAlias(fromItem, tableAliasMap);

        List<Join> joins = plainSelect.getJoins();
        if (joins != null) {
            for (Join join : joins) {
                extractTableAlias(join.getFromItem(), tableAliasMap);
            }
        }

        for (SelectItem<?> item : selectItems) {
            Expression expr = item.getExpression();
            String alias = item.getAlias() != null ? item.getAlias().getName() : null;

            if (expr instanceof Column col) {
                String tableName = extractTableNameFromColumn(col, tableAliasMap);
                String colName = col.getColumnName();
                LineageNode node = LineageNode.of(tableName, colName);
                nodes.add(node);
                if (alias != null) {
                    aliasMap.put(alias, colName);
                } else {
                    aliasMap.put(colName, colName);
                }
            } else if (expr instanceof Function func) {
                Set<LineageNode> funcColumns = new HashSet<>();
                extractColumnsFromExpression(func, funcColumns, null);
                for (LineageNode node : funcColumns) {
                    nodes.add(node);
                    if (alias != null) {
                        aliasMap.put(alias, node.getColumnName());
                    }
                }
            }
        }
    }

    private void extractTableAlias(FromItem fromItem, Map<String, String> tableAliasMap) {
        if (fromItem instanceof Table table) {
            String tableName = extractTableName(table);
            if (table.getAlias() != null) {
                tableAliasMap.put(table.getAlias().getName(), tableName);
            }
            tableAliasMap.put(tableName, tableName);
        }
    }

    private String extractTableNameFromColumn(Column col, Map<String, String> tableAliasMap) {
        Table table = col.getTable();
        if (table != null) {
            String tableRef = extractTableName(table);
            return tableAliasMap.getOrDefault(tableRef, tableRef);
        }
        return tableAliasMap.values().stream().findFirst().orElse("UNKNOWN");
    }

    private void extractColumnsFromExpression(Expression expr, Set<LineageNode> nodes, String defaultTable) {
        if (expr == null) {
            return;
        }

        switch (expr) {
            case Column col -> {
                Table table = col.getTable();
                String tableName = table != null ? extractTableName(table) : defaultTable;
                if (tableName != null) {
                    nodes.add(LineageNode.of(tableName, col.getColumnName()));
                }
            }
            case Function func -> {
                List<Expression> params = func.getParameters() != null ? func.getParameters().getExpressions() : null;
                if (params != null) {
                    for (Expression param : params) {
                        extractColumnsFromExpression(param, nodes, defaultTable);
                    }
                }
            }
            case BinaryExpression binExpr -> {
                extractColumnsFromExpression(binExpr.getLeftExpression(), nodes, defaultTable);
                extractColumnsFromExpression(binExpr.getRightExpression(), nodes, defaultTable);
            }
            case AndExpression andExpr -> {
                extractColumnsFromExpression(andExpr.getLeftExpression(), nodes, defaultTable);
                extractColumnsFromExpression(andExpr.getRightExpression(), nodes, defaultTable);
            }
            case OrExpression orExpr -> {
                extractColumnsFromExpression(orExpr.getLeftExpression(), nodes, defaultTable);
                extractColumnsFromExpression(orExpr.getRightExpression(), nodes, defaultTable);
            }
            case Parenthesis parenExpr -> {
                extractColumnsFromExpression(parenExpr.getExpression(), nodes, defaultTable);
            }
            case CaseWhenClause caseWhen -> {
                extractColumnsFromExpression(caseWhen.getWhenExpression(), nodes, defaultTable);
                extractColumnsFromExpression(caseWhen.getThenExpression(), nodes, defaultTable);
            }
            case CaseExpression caseExpr -> {
                extractColumnsFromExpression(caseExpr.getSwitchExpression(), nodes, defaultTable);
                if (caseExpr.getWhenClauses() != null) {
                    for (CaseWhenClause whenClause : caseExpr.getWhenClauses()) {
                        extractColumnsFromExpression(whenClause, nodes, defaultTable);
                    }
                }
                extractColumnsFromExpression(caseExpr.getElseExpression(), nodes, defaultTable);
            }
            default -> {
            }
        }
    }

    private String extractTableName(Table table) {
        String name = table.getName();
        if (table.getSchemaName() != null) {
            return table.getSchemaName() + "." + name;
        }
        return name;
    }
}
