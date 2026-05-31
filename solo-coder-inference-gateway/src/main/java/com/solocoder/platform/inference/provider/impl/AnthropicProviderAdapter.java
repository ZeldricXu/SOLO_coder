package com.solocoder.platform.inference.provider.impl;

import com.solocoder.platform.inference.model.InferenceRequest;
import com.solocoder.platform.inference.model.InferenceResponse;
import com.solocoder.platform.inference.model.ModelProvider;
import com.solocoder.platform.inference.provider.ProviderAdapter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

@Slf4j
@Component
public class AnthropicProviderAdapter implements ProviderAdapter {

    private static final String PROVIDER_TYPE = "anthropic";

    @Override
    public String getProviderType() {
        return PROVIDER_TYPE;
    }

    @Override
    public InferenceResponse invoke(InferenceRequest request, ModelProvider provider) {
        long start = System.currentTimeMillis();
        try {
            log.info("Invoking Anthropic provider: model={}, endpoint={}", request.getModelType(), provider.getEndpoint());
            Thread.sleep(60);
            long latency = System.currentTimeMillis() - start;
            return InferenceResponse.builder()
                    .requestId(request.getRequestId())
                    .providerId(provider.getProviderId())
                    .modelId(request.getModelType())
                    .content("Anthropic response for: " + request.getPrompt())
                    .latencyMs(latency)
                    .tokenCount(request.getPrompt().length() / 4)
                    .status(InferenceResponse.InferenceStatus.SUCCESS)
                    .timestamp(LocalDateTime.now())
                    .build();
        } catch (Exception e) {
            return InferenceResponse.builder()
                    .requestId(request.getRequestId())
                    .providerId(provider.getProviderId())
                    .status(InferenceResponse.InferenceStatus.MODEL_ERROR)
                    .errorMessage(e.getMessage())
                    .latencyMs(System.currentTimeMillis() - start)
                    .timestamp(LocalDateTime.now())
                    .build();
        }
    }

    @Override
    public boolean healthCheck(ModelProvider provider) {
        return provider.getStatus() == ModelProvider.ProviderStatus.ACTIVE;
    }
}
