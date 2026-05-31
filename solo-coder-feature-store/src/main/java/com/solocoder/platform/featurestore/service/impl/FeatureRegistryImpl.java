package com.solocoder.platform.featurestore.service.impl;

import com.solocoder.platform.common.exception.BusinessException;
import com.solocoder.platform.featurestore.model.FeatureDefinition;
import com.solocoder.platform.featurestore.service.FeatureRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
public class FeatureRegistryImpl implements FeatureRegistry {

    private final Map<String, FeatureDefinition> registry = new ConcurrentHashMap<>();

    @Override
    public FeatureDefinition register(FeatureDefinition definition) {
        if (registry.containsKey(definition.getFeatureId())) {
            throw new BusinessException("Feature already registered: " + definition.getFeatureId());
        }
        FeatureDefinition saved = FeatureDefinition.builder()
                .featureId(definition.getFeatureId())
                .name(definition.getName())
                .description(definition.getDescription())
                .type(definition.getType())
                .owner(definition.getOwner())
                .version(definition.getVersion() != null ? definition.getVersion() : "1.0.0")
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .tags(definition.getTags())
                .build();
        registry.put(saved.getFeatureId(), saved);
        log.info("Feature registered: id={}, name={}, type={}", saved.getFeatureId(), saved.getName(), saved.getType());
        return saved;
    }

    @Override
    public Optional<FeatureDefinition> getFeature(String featureId) {
        return Optional.ofNullable(registry.get(featureId));
    }

    @Override
    public List<FeatureDefinition> listFeatures() {
        return new ArrayList<>(registry.values());
    }

    @Override
    public FeatureDefinition updateFeature(FeatureDefinition definition) {
        FeatureDefinition existing = registry.get(definition.getFeatureId());
        if (existing == null) {
            throw new BusinessException("Feature not found: " + definition.getFeatureId());
        }
        FeatureDefinition updated = FeatureDefinition.builder()
                .featureId(existing.getFeatureId())
                .name(definition.getName() != null ? definition.getName() : existing.getName())
                .description(definition.getDescription() != null ? definition.getDescription() : existing.getDescription())
                .type(definition.getType() != null ? definition.getType() : existing.getType())
                .owner(definition.getOwner() != null ? definition.getOwner() : existing.getOwner())
                .version(existing.getVersion())
                .createdAt(existing.getCreatedAt())
                .updatedAt(LocalDateTime.now())
                .tags(definition.getTags() != null ? definition.getTags() : existing.getTags())
                .build();
        registry.put(updated.getFeatureId(), updated);
        log.info("Feature updated: id={}", updated.getFeatureId());
        return updated;
    }

    @Override
    public void deleteFeature(String featureId) {
        FeatureDefinition removed = registry.remove(featureId);
        if (removed == null) {
            throw new BusinessException("Feature not found: " + featureId);
        }
        log.info("Feature deleted: id={}", featureId);
    }
}
