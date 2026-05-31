package com.solocoder.platform.inference.service;

import com.solocoder.platform.inference.model.ModelProvider;

import java.util.List;
import java.util.Optional;

public interface ProviderRegistry {

    void register(ModelProvider provider);

    void unregister(String providerId);

    Optional<ModelProvider> getProvider(String providerId);

    List<ModelProvider> getAllProviders();

    List<ModelProvider> getProvidersByModel(String modelType);

    void updateStatus(String providerId, ModelProvider.ProviderStatus status);
}
