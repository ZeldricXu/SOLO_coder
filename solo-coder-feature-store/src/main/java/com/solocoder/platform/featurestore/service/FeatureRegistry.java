package com.solocoder.platform.featurestore.service;

import com.solocoder.platform.featurestore.model.FeatureDefinition;

import java.util.List;
import java.util.Optional;

public interface FeatureRegistry {

    FeatureDefinition register(FeatureDefinition definition);

    Optional<FeatureDefinition> getFeature(String featureId);

    List<FeatureDefinition> listFeatures();

    FeatureDefinition updateFeature(FeatureDefinition definition);

    void deleteFeature(String featureId);
}
