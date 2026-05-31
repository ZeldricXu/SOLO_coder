package com.cdcsync.streamquery.plan;

import com.alibaba.fastjson2.JSON;
import com.cdcsync.common.exception.BusinessException;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.SelectBody;

import java.util.HashMap;
import java.util.Map;

public class SqlParser {

    public static LogicalPlan parse(String sql) {
        try {
            Statement statement = CCJSqlParserUtil.parse(sql);
            if (!(statement instanceof Select select)) {
                throw new BusinessException("Only SELECT statements are supported");
            }
            return parseSelect(select);
        } catch (Exception e) {
            throw new BusinessException("Failed to parse SQL: " + e.getMessage(), e);
        }
    }

    private static LogicalPlan parseSelect(Select select) {
        SelectBody selectBody = select.getSelectBody();
        if (selectBody instanceof PlainSelect plainSelect) {
            return parsePlainSelect(plainSelect);
        }
        throw new BusinessException("Unsupported SELECT type: " + selectBody.getClass().getSimpleName());
    }

    private static LogicalPlan parsePlainSelect(PlainSelect plainSelect) {
        LogicalPlan projectPlan = new LogicalPlan("PROJECT") {};
        projectPlan.setProperty("selectItems", plainSelect.getSelectItems().toString());

        if (plainSelect.getFromItem() != null) {
            LogicalPlan scanPlan = new LogicalPlan("SCAN") {};
            scanPlan.setProperty("table", plainSelect.getFromItem().toString());
            projectPlan.addChild(scanPlan);
        }

        if (plainSelect.getWhere() != null) {
            LogicalPlan filterPlan = new LogicalPlan("FILTER") {};
            filterPlan.setProperty("condition", plainSelect.getWhere().toString());
            LogicalPlan scanPlan = projectPlan.getChildren().get(0);
            filterPlan.addChild(scanPlan);
            projectPlan.getChildren().clear();
            projectPlan.addChild(filterPlan);
        }

        if (plainSelect.getOrderByElements() != null && !plainSelect.getOrderByElements().isEmpty()) {
            LogicalPlan sortPlan = new LogicalPlan("SORT") {};
            sortPlan.setProperty("orderElements", plainSelect.getOrderByElements().toString());
            sortPlan.addChild(projectPlan);
            return sortPlan;
        }

        return projectPlan;
    }

    public static String toJson(LogicalPlan plan) {
        return JSON.toJSONString(plan);
    }

    public static LogicalPlan fromJson(String json) {
        return JSON.parseObject(json, LogicalPlan.class);
    }

    public static Map<String, Object> explain(LogicalPlan plan) {
        Map<String, Object> result = new HashMap<>();
        result.put("planType", plan.getPlanType());
        result.put("properties", plan.getProperties());
        if (!plan.getChildren().isEmpty()) {
            result.put("children", plan.getChildren().stream()
                    .map(SqlParser::explain)
                    .toList());
        }
        return result;
    }
}