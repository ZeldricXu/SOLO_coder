package com.tsdbproxy.query.stream.impl;

import cn.hutool.core.util.StrUtil;
import com.tsdbproxy.query.stream.model.LogicalPlan;
import com.tsdbproxy.query.stream.model.QueryStatement;
import com.tsdbproxy.query.stream.spi.SqlSyntaxParser;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Component
public class RegexSqlParser implements SqlSyntaxParser {

    private static final Pattern SELECT_PATTERN = Pattern.compile(
            "SELECT\\s+(.*?)\\s+FROM\\s+(.*?)(?:\\s+WHERE\\s+(.*?))?(?:\\s+GROUP\\s+BY\\s+(.*?))?(?:\\s+ORDER\\s+BY\\s+(.*?))?(?:\\s+HAVING\\s+(.*?))?(?:\\s+LIMIT\\s+(\\d+))?$",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    @Override
    public LogicalPlan parse(QueryStatement statement) {
        log.info("解析SQL: {}", statement.getSql());

        String normalizedSql = statement.getSql().trim().replaceAll("\\s+", " ");
        Matcher matcher = SELECT_PATTERN.matcher(normalizedSql);

        if (!matcher.find()) {
            throw new IllegalArgumentException("不支持的SQL格式: " + statement.getSql());
        }

        LogicalPlan.LogicalPlanBuilder builder = LogicalPlan.builder()
                .operator("SELECT");

        String projectionStr = matcher.group(1);
        String tableStr = matcher.group(2);
        String conditionStr = matcher.group(3);
        String groupByStr = matcher.group(4);
        String orderByStr = matcher.group(5);
        String limitStr = matcher.group(7);

        List<String> projections = new ArrayList<>();
        if ("*".equals(projectionStr.trim())) {
            projections.add("*");
        } else {
            projections.addAll(splitAndTrim(projectionStr));
        }
        builder.projections(projections);
        builder.tables(splitAndTrim(tableStr));

        if (StrUtil.isNotBlank(conditionStr)) {
            builder.condition(conditionStr.trim());
        }

        if (StrUtil.isNotBlank(groupByStr)) {
            builder.groupBy(splitAndTrim(groupByStr));
        }

        if (StrUtil.isNotBlank(orderByStr)) {
            builder.orderBy(splitAndTrim(orderByStr));
        }

        if (StrUtil.isNotBlank(limitStr)) {
            builder.limit(Integer.parseInt(limitStr.trim()));
        }

        return builder.build();
    }

    private List<String> splitAndTrim(String str) {
        return Arrays.stream(str.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }
}
