package com.tsdbproxy.query.stream.spi;

import com.tsdbproxy.query.stream.model.LogicalPlan;

import java.util.List;

public interface LogicalOptimizer {

    List<String> optimize(LogicalPlan plan);
}
