package com.tsdbproxy.query.stream.impl;

import cn.hutool.json.JSONUtil;
import com.tsdbproxy.common.entity.QueryPlan;
import com.tsdbproxy.common.mapper.QueryPlanMapper;
import com.tsdbproxy.query.stream.model.ParseResult;
import com.tsdbproxy.query.stream.spi.PlanRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class MybatisPlanRepository implements PlanRepository {

    private final QueryPlanMapper queryPlanMapper;

    @Override
    public void save(ParseResult result) {
        QueryPlan entity = new QueryPlan();
        entity.setSqlText(result.getSql());
        entity.setLogicalPlan(JSONUtil.toJsonStr(result.getLogicalPlan()));
        entity.setPhysicalPlan(JSONUtil.toJsonStr(result.getPhysicalPlan()));
        entity.setExecutionTimeMs(result.getExecutionTimeMs());
        entity.setOptimizationRules(String.join(",", result.getOptimizationRules()));
        queryPlanMapper.insert(entity);
    }
}
