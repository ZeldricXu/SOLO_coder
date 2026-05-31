package com.tsdbproxy.query.stream.spi;

import com.tsdbproxy.query.stream.model.LogicalPlan;
import com.tsdbproxy.query.stream.model.ParseResult;
import com.tsdbproxy.query.stream.model.PhysicalPlan;

import java.util.List;

public interface PlanRepository {

    void save(ParseResult result);
}
