package com.tsdbproxy.lifecycle.strategy;

import com.tsdbproxy.common.entity.LifecyclePolicy;

public interface LifecycleStrategy {

    void execute(LifecyclePolicy policy);

    String getOperationType();
}
