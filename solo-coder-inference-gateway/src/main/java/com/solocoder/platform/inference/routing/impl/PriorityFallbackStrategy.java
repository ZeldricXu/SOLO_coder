package com.solocoder.platform.inference.routing.impl;

import com.solocoder.platform.inference.model.InferenceRequest;
import com.solocoder.platform.inference.model.InferenceResponse;
import com.solocoder.platform.inference.model.ModelProvider;
import com.solocoder.platform.inference.provider.ProviderAdapter;
import com.solocoder.platform.inference.routing.FallbackStrategy;
import com.solocoder.platform.inference.routing.LoadBalancer;
import com.solocoder.platform.inference.service.ProviderRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class PriorityFallbackStrategy implements FallbackStrategy {

    private final ProviderRegistry providerRegistry;
    private final LoadBalancer loadBalancer;
    private final List<ProviderAdapter> adapters;

    @Override
    public InferenceResponse executeWithFallback(InferenceRequest request) {
        List<ModelProvider> providers = providerRegistry.getProvidersByModel(request.getModelType());
        if (providers.isEmpty()) {
            return InferenceResponse.builder()
                    .requestId(request.getRequestId())
                    .status(InferenceResponse.InferenceStatus.MODEL_ERROR)
                    .errorMessage("No provider available for model: " + request.getModelType())
                    .build();
        }

        providers.sort((a, b) -> Integer.compare(a.getPriority(), b.getPriority()));

        InferenceResponse lastFailure = null;
        for (ModelProvider provider : providers) {
            ProviderAdapter adapter = findAdapter(provider);
            if (adapter == null || !adapter.healthCheck(provider)) continue;

            try {
                InferenceResponse response = adapter.invoke(request, provider);
                if (response.getStatus() == InferenceResponse.InferenceStatus.SUCCESS) {
                    return response;
                }
                lastFailure = response;
                log.warn("Provider {} failed, trying next fallback: {}", provider.getProviderId(), response.getErrorMessage());
            } catch (Exception e) {
                log.warn("Provider {} threw exception, trying next fallback", provider.getProviderId(), e);
                lastFailure = InferenceResponse.builder()
                        .requestId(request.getRequestId())
                        .providerId(provider.getProviderId())
                        .status(InferenceResponse.InferenceStatus.MODEL_ERROR)
                        .errorMessage(e.getMessage())
                        .build();
            }
        }

        if (lastFailure != null) {
            return lastFailure;
        }

        return InferenceResponse.builder()
                .requestId(request.getRequestId())
                .status(InferenceResponse.InferenceStatus.MODEL_ERROR)
                .errorMessage("All providers failed or unavailable")
                .build();
    }

    private ProviderAdapter findAdapter(ModelProvider provider) {
        return adapters.stream()
                .filter(a -> a.getProviderType().equalsIgnoreCase(provider.getName()))
                .findFirst()
                .orElse(null);
    }
}
