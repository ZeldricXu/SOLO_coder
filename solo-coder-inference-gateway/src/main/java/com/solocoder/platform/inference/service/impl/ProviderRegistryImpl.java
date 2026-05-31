package com.solocoder.platform.inference.service.impl;

import com.solocoder.platform.inference.model.ModelProvider;
import com.solocoder.platform.inference.service.ProviderRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Slf4j
@Service
public class ProviderRegistryImpl implements ProviderRegistry {

    private final Map<String, ModelProvider> registry = new ConcurrentHashMap<>();

    @Override
    public void register(ModelProvider provider) {
        registry.put(provider.getProviderId(), provider);
        log.info("Provider registered: id={}, name={}", provider.getProviderId(), provider.getName());
    }

    @Override
    public void unregister(String providerId) {
        registry.remove(providerId);
        log.info("Provider unregistered: id={}", providerId);
    }

    @Override
    public Optional<ModelProvider> getProvider(String providerId) {
        return Optional.ofNullable(registry.get(providerId));
    }

    @Override
    public List<ModelProvider> getAllProviders() {
        return new ArrayList<>(registry.values());
    }

    @Override
    public List<ModelProvider> getProvidersByModel(String modelType) {
        return registry.values().stream()
                .filter(p -> p.getSupportedModels() != null && p.getSupportedModels().contains(modelType))
                .filter(p -> p.getStatus() != ModelProvider.ProviderStatus.OFFLINE)
                .collect(Collectors.toList());
    }

    @Override
    public void updateStatus(String providerId, ModelProvider.ProviderStatus status) {
        ModelProvider provider = registry.get(providerId);
        if (provider != null) {
            provider.setStatus(status);
            log.info("Provider status updated: id={}, newStatus={}", providerId, status);
        }
    }
}
