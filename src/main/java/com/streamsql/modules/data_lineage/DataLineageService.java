package com.streamsql.modules.data_lineage;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.streamsql.common.PageResult;
import com.streamsql.dto.LineageParseDTO;
import com.streamsql.entity.LineageEdge;
import com.streamsql.entity.LineageGraph;
import com.streamsql.entity.LineageNode;
import com.streamsql.mapper.LineageEdgeMapper;
import com.streamsql.mapper.LineageGraphMapper;
import com.streamsql.mapper.LineageNodeMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.create.table.CreateTable;
import net.sf.jsqlparser.statement.delete.Delete;
import net.sf.jsqlparser.statement.insert.Insert;
import net.sf.jsqlparser.statement.merge.Merge;
import net.sf.jsqlparser.statement.select.*;
import net.sf.jsqlparser.statement.update.Update;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class DataLineageService {

    private final LineageGraphMapper lineageGraphMapper;
    private final LineageNodeMapper lineageNodeMapper;
    private final LineageEdgeMapper lineageEdgeMapper;
    private final ObjectMapper objectMapper;

    @Transactional(rollbackFor = Exception.class)
    public LineageGraph parseLineage(LineageParseDTO dto) throws JsonProcessingException {
        String sql = dto.getSql();
        log.info("Parsing SQL for data lineage: {}", sql);

        LineageContext context = new LineageContext();

        try {
            Statement statement = CCJSqlParserUtil.parse(sql);
            processStatement(statement, context);
        } catch (Exception e) {
            log.error("Failed to parse SQL for lineage", e);
            throw new IllegalArgumentException("SQL解析失败: " + e.getMessage());
        }

        LineageGraph graph = new LineageGraph();
        graph.setSourceType(dto.getSourceType());
        graph.setSourceSql(sql);
        graph.setGraphData(objectMapper.writeValueAsString(buildGraphData(context)));
        lineageGraphMapper.insert(graph);

        for (LineageNode node : context.nodes.values()) {
            node.setLineageId(graph.getLineageId());
            lineageNodeMapper.insert(node);
        }

        for (LineageEdge edge : context.edges) {
            edge.setLineageId(graph.getLineageId());
            lineageEdgeMapper.insert(edge);
        }

        return graph;
    }

    private void processStatement(Statement statement, LineageContext context) {
        if (statement instanceof Select select) {
            processSelect(select, context);
        } else if (statement instanceof Insert insert) {
            processInsert(insert, context);
        } else if (statement instanceof Update update) {
            processUpdate(update, context);
        } else if (statement instanceof Delete delete) {
            processDelete(delete, context);
        } else if (statement instanceof CreateTable createTable) {
            processCreateTable(createTable, context);
        } else if (statement instanceof Merge merge) {
            processMerge(merge, context);
        }
    }

    private void processSelect(Select select, LineageContext context) {
        SelectBody selectBody = select.getSelectBody();
        if (selectBody instanceof PlainSelect plainSelect) {
            processPlainSelect(plainSelect, context);
        } else if (selectBody instanceof SetOperationList setOpList) {
            for (SelectBody body : setOpList.getSelects()) {
                if (body instanceof PlainSelect plainSelect) {
                    processPlainSelect(plainSelect, context);
                }
            }
        }
    }

    private void processPlainSelect(PlainSelect plainSelect, LineageContext context) {
        FromItem fromItem = plainSelect.getFromItem();
        List<Table> sourceTables = new ArrayList<>();
        extractTables(fromItem, sourceTables);

        if (plainSelect.getJoins() != null) {
            for (Join join : plainSelect.getJoins()) {
                extractTables(join.getRightItem(), sourceTables);
            }
        }

        for (Table table : sourceTables) {
            String tableName = getTableName(table);
            getOrCreateNode(context, tableName, "TABLE", null);
        }

        List<SelectItem> selectItems = plainSelect.getSelectItems();
        for (SelectItem item : selectItems) {
            if (item instanceof SelectExpressionItem exprItem) {
                processSelectExpression(exprItem, sourceTables, context);
            }
        }
    }

    private void extractTables(FromItem fromItem, List<Table> tables) {
        if (fromItem instanceof Table table) {
            tables.add(table);
        } else if (fromItem instanceof SubSelect subSelect) {
            if (subSelect.getSelect().getSelectBody() instanceof PlainSelect plainSelect) {
                extractTables(plainSelect.getFromItem(), tables);
            }
        } else if (fromItem instanceof ParenthesisFromItem parenItem) {
            extractTables(parenItem.getFromItem(), tables);
        }
    }

    private void processSelectExpression(SelectExpressionItem exprItem, List<Table> sourceTables, LineageContext context) {
        String columnName = exprItem.getAlias() != null ?
                exprItem.getAlias().getName() : extractColumnName(exprItem.getExpression());

        String targetNodeId = "result." + columnName;
        LineageNode targetNode = getOrCreateNode(context, targetNodeId, "COLUMN", null);

        for (Table table : sourceTables) {
            String tableName = getTableName(table);
            String sourceNodeId = tableName + "." + columnName;
            LineageNode sourceNode = context.nodes.get(sourceNodeId);
            if (sourceNode == null) {
                sourceNodeId = tableName + ".*";
                sourceNode = context.nodes.get(sourceNodeId);
            }
            if (sourceNode != null) {
                createEdge(context, sourceNode.getNodeId(), targetNode.getNodeId(), "PROJECTION");
            }
        }
    }

    private String extractColumnName(net.sf.jsqlparser.expression.Expression expression) {
        if (expression instanceof net.sf.jsqlparser.schema.Column column) {
            return column.getColumnName();
        }
        return expression.toString();
    }

    private void processInsert(Insert insert, LineageContext context) {
        String targetTable = getTableName(insert.getTable());
        LineageNode targetNode = getOrCreateNode(context, targetTable, "TABLE", null);

        if (insert.getSelect() != null) {
            processSelect(insert.getSelect(), context);
            for (LineageNode node : context.nodes.values()) {
                if (node.getNodeType().equals("TABLE") && !node.getNodeId().equals(targetTable)) {
                    createEdge(context, node.getNodeId(), targetNode.getNodeId(), "INSERT");
                }
            }
        }

        if (insert.getColumns() != null) {
            for (net.sf.jsqlparser.schema.Column col : insert.getColumns()) {
                String colNodeId = targetTable + "." + col.getColumnName();
                getOrCreateNode(context, colNodeId, "COLUMN", null);
                createEdge(context, colNodeId, targetNode.getNodeId(), "BELONGS_TO");
            }
        }
    }

    private void processUpdate(Update update, LineageContext context) {
        for (Table table : update.getTables()) {
            String targetTable = getTableName(table);
            LineageNode targetNode = getOrCreateNode(context, targetTable, "TABLE", null);

            if (update.getWhere() != null) {
                String sourceNodeId = targetTable + ".filter";
                LineageNode sourceNode = getOrCreateNode(context, sourceNodeId, "FILTER", null);
                createEdge(context, sourceNode.getNodeId(), targetNode.getNodeId(), "UPDATE");
            }
        }
    }

    private void processDelete(Delete delete, LineageContext context) {
        String targetTable = getTableName(delete.getTable());
        LineageNode targetNode = getOrCreateNode(context, targetTable, "TABLE", null);

        if (delete.getWhere() != null) {
            String sourceNodeId = targetTable + ".filter";
            LineageNode sourceNode = getOrCreateNode(context, sourceNodeId, "FILTER", null);
            createEdge(context, sourceNode.getNodeId(), targetNode.getNodeId(), "DELETE");
        }
    }

    private void processCreateTable(CreateTable createTable, LineageContext context) {
        String tableName = getTableName(createTable.getTable());
        LineageNode tableNode = getOrCreateNode(context, tableName, "TABLE", null);

        if (createTable.getColumnDefinitions() != null) {
            for (net.sf.jsqlparser.statement.create.table.ColDefinition colDef : createTable.getColumnDefinitions()) {
                String colNodeId = tableName + "." + colDef.getColumnName();
                LineageNode colNode = getOrCreateNode(context, colNodeId, "COLUMN",
                        Map.of("dataType", colDef.getColDataType().toString()));
                createEdge(context, colNode.getNodeId(), tableNode.getNodeId(), "BELONGS_TO");
            }
        }

        if (createTable.getSelect() != null) {
            processSelect(createTable.getSelect(), context);
            for (LineageNode node : new ArrayList<>(context.nodes.values())) {
                if (node.getNodeType().equals("TABLE") && !node.getNodeId().equals(tableName)) {
                    createEdge(context, node.getNodeId(), tableNode.getNodeId(), "CREATE_AS");
                }
            }
        }
    }

    private void processMerge(Merge merge, LineageContext context) {
        String targetTable = getTableName(merge.getTable());
        LineageNode targetNode = getOrCreateNode(context, targetTable, "TABLE", null);

        if (merge.getUsingSelect() != null) {
            processSelect(merge.getUsingSelect(), context);
        }

        String sourceTable = getTableName(merge.getUsingTable());
        if (sourceTable != null) {
            LineageNode sourceNode = getOrCreateNode(context, sourceTable, "TABLE", null);
            createEdge(context, sourceNode.getNodeId(), targetNode.getNodeId(), "MERGE");
        }
    }

    private LineageNode getOrCreateNode(LineageContext context, String nodeName, String nodeType, Map<String, Object> metadata) {
        return context.nodes.computeIfAbsent(nodeName, k -> {
            LineageNode node = new LineageNode();
            node.setNodeId(UUID.randomUUID().toString());
            node.setNodeType(nodeType);
            node.setNodeName(k);
            try {
                if (metadata != null) {
                    node.setNodeMetadata(objectMapper.writeValueAsString(metadata));
                }
            } catch (JsonProcessingException e) {
                log.warn("Failed to serialize node metadata", e);
            }
            context.nodeIdMap.put(k, node.getNodeId());
            return node;
        });
    }

    private void createEdge(LineageContext context, String sourceName, String targetName, String edgeType) {
        String sourceId = context.nodeIdMap.get(sourceName);
        String targetId = context.nodeIdMap.get(targetName);

        if (sourceId == null || targetId == null) {
            return;
        }

        String edgeKey = sourceId + "->" + targetId + ":" + edgeType;
        if (context.edgeKeys.contains(edgeKey)) {
            return;
        }

        LineageEdge edge = new LineageEdge();
        edge.setEdgeId(UUID.randomUUID().toString());
        edge.setSourceNodeId(sourceId);
        edge.setTargetNodeId(targetId);
        edge.setEdgeType(edgeType);

        context.edges.add(edge);
        context.edgeKeys.add(edgeKey);
    }

    private String getTableName(Table table) {
        if (table == null) return null;
        StringBuilder sb = new StringBuilder();
        if (table.getSchemaName() != null) {
            sb.append(table.getSchemaName()).append(".");
        }
        sb.append(table.getName());
        return sb.toString();
    }

    private Map<String, Object> buildGraphData(LineageContext context) {
        Map<String, Object> graph = new LinkedHashMap<>();
        List<Map<String, Object>> nodes = new ArrayList<>();
        List<Map<String, Object>> edges = new ArrayList<>();

        for (LineageNode node : context.nodes.values()) {
            Map<String, Object> nodeMap = new LinkedHashMap<>();
            nodeMap.put("id", node.getNodeId());
            nodeMap.put("name", node.getNodeName());
            nodeMap.put("type", node.getNodeType());
            nodes.add(nodeMap);
        }

        for (LineageEdge edge : context.edges) {
            Map<String, Object> edgeMap = new LinkedHashMap<>();
            edgeMap.put("id", edge.getEdgeId());
            edgeMap.put("source", edge.getSourceNodeId());
            edgeMap.put("target", edge.getTargetNodeId());
            edgeMap.put("type", edge.getEdgeType());
            edges.add(edgeMap);
        }

        graph.put("nodes", nodes);
        graph.put("edges", edges);
        return graph;
    }

    public LineageGraph getLineageGraph(String lineageId) {
        return lineageGraphMapper.selectById(lineageId);
    }

    public PageResult<LineageGraph> listLineageGraphs(int page, int size, String sourceType) {
        LambdaQueryWrapper<LineageGraph> wrapper = new LambdaQueryWrapper<>();
        if (sourceType != null) {
            wrapper.eq(LineageGraph::getSourceType, sourceType);
        }
        wrapper.orderByDesc(LineageGraph::getCreatedAt);

        IPage<LineageGraph> pageResult = lineageGraphMapper.selectPage(new Page<>(page, size), wrapper);
        return PageResult.of(pageResult.getRecords(), pageResult.getTotal(), page, size);
    }

    public List<LineageNode> getLineageNodes(String lineageId) {
        return lineageNodeMapper.selectList(new LambdaQueryWrapper<LineageNode>()
                .eq(LineageNode::getLineageId, lineageId));
    }

    public List<LineageEdge> getLineageEdges(String lineageId) {
        return lineageEdgeMapper.selectList(new LambdaQueryWrapper<LineageEdge>()
                .eq(LineageEdge::getLineageId, lineageId));
    }

    @Transactional(rollbackFor = Exception.class)
    public void deleteLineageGraph(String lineageId) {
        lineageEdgeMapper.delete(new LambdaQueryWrapper<LineageEdge>()
                .eq(LineageEdge::getLineageId, lineageId));
        lineageNodeMapper.delete(new LambdaQueryWrapper<LineageNode>()
                .eq(LineageNode::getLineageId, lineageId));
        lineageGraphMapper.deleteById(lineageId);
    }

    private static class LineageContext {
        Map<String, LineageNode> nodes = new LinkedHashMap<>();
        Map<String, String> nodeIdMap = new HashMap<>();
        List<LineageEdge> edges = new ArrayList<>();
        Set<String> edgeKeys = new HashSet<>();
    }
}
