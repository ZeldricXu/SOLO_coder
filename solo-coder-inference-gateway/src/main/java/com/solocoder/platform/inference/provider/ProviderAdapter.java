package com.solocoder.platform.inference.provider;

import com.solocoder.platform.inference.model.InferenceRequest;
import com.solocoder.platform.inference.model.InferenceResponse;
import com.solocoder.platform.inference.model.ModelProvider;

public interface ProviderAdapter {

    String getProviderType();

    InferenceResponse invoke(InferenceRequest request, ModelProvider provider);

    boolean healthCheck(ModelProvider provider);
}
