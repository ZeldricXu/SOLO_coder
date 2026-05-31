package com.streamsql.modules.stream_query;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.streamsql.common.PageResult;
import com.streamsql.dto.StreamQueryDTO;
import com.streamsql.entity.StreamQueryPlan;
import com.streamsql.mapper.StreamQueryPlanMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.calcite.config.Lex;
import org.apache.calcite.sql.*;
import org.apache.calcite.sql.parser.SqlParseException;
import org.apache.calcite.sql.parser.SqlParser;
import org.apache.calcite.sql.parser.SqlParserImplFactory;
import org.apache.calcite.sql.parser.impl.SqlParserImpl;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class StreamQueryParserService {

    private final StreamQueryPlanMapper queryPlanMapper;
    private final ObjectMapper objectMapper;

    @Transactional(rollbackFor = Exception.class)
    public StreamQueryPlan parseAndPlan(StreamQueryDTO dto) throws JsonProcessingException {
        String sql = dto.getSql();
        log.info("Parsing stream SQL: {}", sql);

        SqlParser.Config config = SqlParser.config()
                .withLex(Lex.MYSQL)
                .withParserFactory((SqlParserImplFactory) SqlParserImpl.FACTORY);
        SqlParser parser = SqlParser.create(sql, config);

        SqlNode sqlNode;
        try {
            sqlNode = parser.parseStmt();
        } catch (SqlParseException e) {
            log.error("SQL parse error: {}", e.getMessage());
            throw new IllegalArgumentException("SQL语法错误: " + e.getMessage());
        }

        Map<String, Object> logicalPlan = buildLogicalPlan(sqlNode);
        Map<String, Object> physicalPlan = optimizeAndTranslateToPhysical(logicalPlan);

        StreamQueryPlan plan = new StreamQueryPlan();
        plan.setQueryName(dto.getQueryName());
        plan.setOriginalSql(sql);
        plan.setLogicalPlan(objectMapper.writeValueAsString(logicalPlan));
        plan.setPhysicalPlan(objectMapper.writeValueAsString(physicalPlan));
        plan.setExecutionConfig(objectMapper.writeValueAsString(dto.getExecutionConfig()));
        plan.setStatus("parsed");

        queryPlanMapper.insert(plan);

        return plan;
    }

    private Map<String, Object> buildLogicalPlan(SqlNode sqlNode) {
        Map<String, Object> plan = new LinkedHashMap<>();
        plan.put("nodeType", sqlNode.getKind().name());

        if (sqlNode instanceof SqlSelect select) {
            plan.put("operation", "SELECT");
            plan.put("projection", extractProjection(select.getSelectList()));
            plan.put("from", extractFrom(select.getFrom()));
            
            if (select.getWhere() != null) {
                plan.put("filter", extractExpression(select.getWhere()));
            }
            
            if (select.getGroup() != null) {
                plan.put("groupBy", extractGroupBy(select.getGroup()));
            }
            
            if (select.getOrderList() != null) {
                plan.put("orderBy", extractOrderBy(select.getOrderList()));
            }
            
            if (select.getFetch() != null) {
                plan.put("limit", select.getFetch());
            }
        } else if (sqlNode instanceof SqlInsert insert) {
            plan.put("operation", "INSERT");
            plan.put("targetTable", insert.getTargetTable().toString());
            plan.put("source", buildLogicalPlan(insert.getSource()));
        } else if (sqlNode instanceof SqlUpdate update) {
            plan.put("operation", "UPDATE");
            plan.put("targetTable", update.getTargetTable().toString());
            plan.put("updates", extractUpdateExpressions(update));
            if (update.getCondition() != null) {
                plan.put("filter", extractExpression(update.getCondition()));
            }
        } else if (sqlNode instanceof SqlDelete delete) {
            plan.put("operation", "DELETE");
            plan.put("targetTable", delete.getTargetTable().toString());
            if (delete.getCondition() != null) {
                plan.put("filter", extractExpression(delete.getCondition()));
            }
        }

        return plan;
    }

    private List<Map<String, Object>> extractProjection(SqlNodeList selectList) {
        List<Map<String, Object>> projections = new ArrayList<>();
        for (SqlNode node : selectList) {
            Map<String, Object> expr = new LinkedHashMap<>();
            if (node instanceof SqlIdentifier id) {
                expr.put("type", "column");
                expr.put("name", id.toString());
            } else if (node instanceof SqlCall call) {
                expr.put("type", "function");
                expr.put("function", call.getOperator().getName());
                List<Map<String, Object>> operands = new ArrayList<>();
                for (SqlNode operand : call.getOperandList()) {
                    operands.add(extractExpression(operand));
                }
                expr.put("operands", operands);
            }
            projections.add(expr);
        }
        return projections;
    }

    private Map<String, Object> extractFrom(SqlNode from) {
        Map<String, Object> fromInfo = new LinkedHashMap<>();
        if (from instanceof SqlIdentifier id) {
            fromInfo.put("type", "table");
            fromInfo.put("name", id.toString());
        } else if (from instanceof SqlJoin join) {
            fromInfo.put("type", "join");
            fromInfo.put("joinType", join.getJoinType().name());
            fromInfo.put("left", extractFrom(join.getLeft()));
            fromInfo.put("right", extractFrom(join.getRight()));
            if (join.getCondition() != null) {
                fromInfo.put("condition", extractExpression(join.getCondition()));
            }
        }
        return fromInfo;
    }

    private Map<String, Object> extractExpression(SqlNode node) {
        Map<String, Object> expr = new LinkedHashMap<>();
        
        if (node instanceof SqlIdentifier id) {
            expr.put("type", "column");
            expr.put("value", id.toString());
        } else if (node instanceof SqlLiteral literal) {
            expr.put("type", "literal");
            expr.put("value", literal.toValue());
        } else if (node instanceof SqlCall call) {
            expr.put("type", "operation");
            expr.put("operator", call.getOperator().getName());
            List<Map<String, Object>> operands = new ArrayList<>();
            for (SqlNode operand : call.getOperandList()) {
                operands.add(extractExpression(operand));
            }
            expr.put("operands", operands);
        }
        
        return expr;
    }

    private List<Map<String, Object>> extractGroupBy(SqlNodeList groupBy) {
        List<Map<String, Object>> groups = new ArrayList<>();
        for (SqlNode node : groupBy) {
            groups.add(extractExpression(node));
        }
        return groups;
    }

    private List<Map<String, Object>> extractOrderBy(SqlNodeList orderList) {
        List<Map<String, Object>> orders = new ArrayList<>();
        for (SqlNode node : orderList) {
            if (node instanceof SqlOrderBy orderBy) {
                Map<String, Object> order = new LinkedHashMap<>();
                order.put("expression", extractExpression(orderBy.operand(0)));
                order.put("direction", orderBy.getDirection().name());
                orders.add(order);
            }
        }
        return orders;
    }

    private List<Map<String, Object>> extractUpdateExpressions(SqlUpdate update) {
        List<Map<String, Object>> updates = new ArrayList<>();
        for (int i = 0; i < update.getTargetColumnList().size(); i++) {
            Map<String, Object> updateExpr = new LinkedHashMap<>();
            updateExpr.put("column", update.getTargetColumnList().get(i).toString());
            updateExpr.put("value", extractExpression(update.getSourceExpressionList().get(i)));
            updates.add(updateExpr);
        }
        return updates;
    }

    private Map<String, Object> optimizeAndTranslateToPhysical(Map<String, Object> logicalPlan) {
        Map<String, Object> physicalPlan = new LinkedHashMap<>(logicalPlan);
        
        List<Map<String, Object>> optimizations = new ArrayList<>();
        optimizations.add(Map.of("type", "PUSH_DOWN_FILTER", "description", "将过滤条件下推到数据源"));
        optimizations.add(Map.of("type", "PUSH_DOWN_PROJECTION", "description", "将投影下推到数据源"));
        optimizations.add(Map.of("type", "PARALLEL_EXECUTION", "description", "启用并行执行"));
        
        physicalPlan.put("optimizations", optimizations);
        physicalPlan.put("executionMode", "STREAMING");
        physicalPlan.put("parallelism", 4);
        
        return physicalPlan;
    }

    public StreamQueryPlan getPlan(String planId) {
        return queryPlanMapper.selectById(planId);
    }

    public PageResult<StreamQueryPlan> listPlans(int page, int size, String status) {
        LambdaQueryWrapper<StreamQueryPlan> wrapper = new LambdaQueryWrapper<>();
        if (status != null) {
            wrapper.eq(StreamQueryPlan::getStatus, status);
        }
        wrapper.orderByDesc(StreamQueryPlan::getCreatedAt);

        IPage<StreamQueryPlan> pageResult = queryPlanMapper.selectPage(new Page<>(page, size), wrapper);
        return PageResult.of(pageResult.getRecords(), pageResult.getTotal(), page, size);
    }

    @Transactional(rollbackFor = Exception.class)
    public void deletePlan(String planId) {
        queryPlanMapper.deleteById(planId);
    }

    @Transactional(rollbackFor = Exception.class)
    public StreamQueryPlan executePlan(String planId) {
        StreamQueryPlan plan = queryPlanMapper.selectById(planId);
        if (plan == null) {
            throw new IllegalArgumentException("查询计划不存在: " + planId);
        }

        plan.setStatus("executing");
        queryPlanMapper.updateById(plan);

        log.info("Executing stream query plan: {}", planId);

        plan.setStatus("completed");
        queryPlanMapper.updateById(plan);

        return plan;
    }
}
