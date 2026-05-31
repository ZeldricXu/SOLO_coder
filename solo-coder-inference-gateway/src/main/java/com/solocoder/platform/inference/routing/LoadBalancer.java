package com.solocoder.platform.inference.routing;

import com.solocoder.platform.inference.model.ModelProvider;

import java.util.List;
import java.util.Optional;

public interface LoadBalancer {

    Optional<ModelProvider> select(List<ModelProvider> providers, String modelType);

    String getStrategy();
}
