package com.reviewsystem.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.HashMap;
import java.util.Map;

@Configuration
@ConfigurationProperties(prefix = "review.recommend")
public class RecommendWeightConfig {

    private Map<String, WeightItem> weights = new HashMap<>();

    public Map<String, WeightItem> getWeights() {
        return weights;
    }

    public void setWeights(Map<String, WeightItem> weights) {
        this.weights = weights;
    }

    public WeightItem getWeightByContentType(String contentType) {
        if (contentType == null || !weights.containsKey(contentType)) {
            return weights.getOrDefault("default", new WeightItem());
        }
        return weights.get(contentType);
    }

    public static class WeightItem {
        private double quality = 0.35;
        private double sentiment = 0.25;
        private double heat = 0.20;
        private double time = 0.20;

        public double getQuality() {
            return quality;
        }

        public void setQuality(double quality) {
            this.quality = quality;
        }

        public double getSentiment() {
            return sentiment;
        }

        public void setSentiment(double sentiment) {
            this.sentiment = sentiment;
        }

        public double getHeat() {
            return heat;
        }

        public void setHeat(double heat) {
            this.heat = heat;
        }

        public double getTime() {
            return time;
        }

        public void setTime(double time) {
            this.time = time;
        }

        public boolean validate() {
            double total = quality + sentiment + heat + time;
            return Math.abs(total - 1.0) < 0.001;
        }
    }
}
