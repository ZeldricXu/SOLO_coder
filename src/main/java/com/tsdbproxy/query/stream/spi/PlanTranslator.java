package com.tsdbproxy.query.stream.spi;

import com.tsdbproxy.query.stream.model.LogicalPlan;
import com.tsdbproxy.query.stream.model.PhysicalPlan;

public interface PlanTranslator {

    PhysicalPlan translate(LogicalPlan logicalPlan);
}
