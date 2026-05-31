package com.solocoder.platform.inference.service.impl;

import com.solocoder.platform.common.exception.BusinessException;
import com.solocoder.platform.inference.model.InferenceRequest;
import com.solocoder.platform.inference.model.InferenceResponse;
import com.solocoder.platform.inference.model.ModelProvider;
import com.solocoder.platform.inference.provider.ProviderAdapter;
import com.solocoder.platform.inference.routing.FallbackStrategy;
import com.solocoder.platform.inference.routing.LoadBalancer;
import com.solocoder.platform.inference.service.InferenceRouterService;
import com.solocoder.platform.inference.service.ProviderRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class InferenceRouterServiceImpl implements InferenceRouterService {

    private final ProviderRegistry providerRegistry;
    private final LoadBalancer loadBalancer;
    private final FallbackStrategy fallbackStrategy;
    private final List<ProviderAdapter> adapters;

    @Override
    public InferenceResponse route(InferenceRequest request) {
        List<ModelProvider> providers = providerRegistry.getProvidersByModel(request.getModelType());
        if (providers.isEmpty()) {
            throw new BusinessException("No provider available for model: " + request.getModelType());
        }

        Optional<ModelProvider> selected = loadBalancer.select(providers, request.getModelType());
        if (selected.isEmpty()) {
            throw new BusinessException("Load balancer could not select a provider");
        }

        ModelProvider provider = selected.get();
        ProviderAdapter adapter = findAdapter(provider);
        if (adapter == null) {
            throw new BusinessException("No adapter found for provider: " + provider.getName());
        }

        log.info("Routing request: id={}, model={}, provider={}", request.getRequestId(), request.getModelType(), provider.getProviderId());
        return adapter.invoke(request, provider);
    }

    @Override
    public InferenceResponse routeWithFallback(InferenceRequest request) {
        log.info("Routing with fallback: id={}, model={}", request.getRequestId(), request.getModelType());
        return fallbackStrategy.executeWithFallback(request);
    }

    private ProviderAdapter findAdapter(ModelProvider provider) {
        return adapters.stream()
                .filter(a -> a.getProviderType().equalsIgnoreCase(provider.getName()))
                .findFirst()
                .orElse(null);
    }
}
