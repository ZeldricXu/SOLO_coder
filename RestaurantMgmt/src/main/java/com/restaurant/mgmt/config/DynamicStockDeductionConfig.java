package com.restaurant.mgmt.config;

import jakarta.annotation.PostConstruct;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

@Component
@ConfigurationProperties(prefix = "restaurant.stock.deduction")
public class DynamicStockDeductionConfig {

    private List<DeductionStrategy> strategies = new ArrayList<>();
    private Map<String, DeductionStrategy> ingredientStrategies = new HashMap<>();
    private Map<String, DeductionStrategy> categoryStrategies = new HashMap<>();
    
    private final ReadWriteLock lock = new ReentrantReadWriteLock();

    private double warningLevelHighRatio = 0.3;
    private double warningLevelMediumRatio = 0.6;
    private String defaultStrategy = "confirm_deduct";

    @PostConstruct
    public void initDefaultStrategies() {
        if (strategies == null || strategies.isEmpty()) {
            strategies = new ArrayList<>();
            
            DeductionStrategy criticalStrategy = new DeductionStrategy();
            criticalStrategy.setName("critical");
            criticalStrategy.setStrategyType("pre_deduct");
            criticalStrategy.setDescription("关键食材-预扣减确保可用");
            criticalStrategy.setIngredientIds(List.of("ingredient_001", "ingredient_002", "ingredient_003"));
            criticalStrategy.setCategories(List.of("肉类", "海鲜"));
            strategies.add(criticalStrategy);
            
            DeductionStrategy normalStrategy = new DeductionStrategy();
            normalStrategy.setName("normal");
            normalStrategy.setStrategyType("confirm_deduct");
            normalStrategy.setDescription("普通食材-确认扣减减少浪费");
            normalStrategy.setCategories(List.of("蔬菜", "调料", "饮品", "主食"));
            strategies.add(normalStrategy);
        }
        buildStrategyMaps();
    }

    private void buildStrategyMaps() {
        lock.writeLock().lock();
        try {
            ingredientStrategies = new HashMap<>();
            categoryStrategies = new HashMap<>();
            
            for (DeductionStrategy strategy : strategies) {
                if (strategy.getIngredientIds() != null) {
                    for (String ingredientId : strategy.getIngredientIds()) {
                        ingredientStrategies.put(ingredientId, strategy);
                    }
                }
                if (strategy.getCategories() != null) {
                    for (String category : strategy.getCategories()) {
                        categoryStrategies.put(category, strategy);
                    }
                }
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    public void setStrategies(List<DeductionStrategy> strategies) {
        lock.writeLock().lock();
        try {
            this.strategies = new ArrayList<>(strategies);
            buildStrategyMaps();
        } finally {
            lock.writeLock().unlock();
        }
    }

    public List<DeductionStrategy> getStrategies() {
        lock.readLock().lock();
        try {
            return new ArrayList<>(strategies);
        } finally {
            lock.readLock().unlock();
        }
    }

    public String getDeductionStrategy(String ingredientId, String category) {
        lock.readLock().lock();
        try {
            DeductionStrategy strategy = ingredientStrategies.get(ingredientId);
            if (strategy != null) {
                return strategy.getStrategyType();
            }
            
            if (category != null) {
                strategy = categoryStrategies.get(category);
                if (strategy != null) {
                    return strategy.getStrategyType();
                }
            }
            
            return defaultStrategy;
        } finally {
            lock.readLock().unlock();
        }
    }

    public boolean shouldPreDeduct(String ingredientId, String category) {
        return "pre_deduct".equals(getDeductionStrategy(ingredientId, category));
    }

    public boolean shouldConfirmDeduct(String ingredientId, String category) {
        return "confirm_deduct".equals(getDeductionStrategy(ingredientId, category));
    }

    public String getWarningLevel(double currentQuantity, double warningThreshold) {
        if (warningThreshold <= 0) {
            return "low";
        }
        double ratio = currentQuantity / warningThreshold;
        if (ratio <= warningLevelHighRatio) {
            return "high";
        } else if (ratio <= warningLevelMediumRatio) {
            return "medium";
        } else {
            return "low";
        }
    }

    public void addIngredientToStrategy(String strategyName, String ingredientId) {
        lock.writeLock().lock();
        try {
            for (DeductionStrategy strategy : strategies) {
                if (strategy.getName().equals(strategyName)) {
                    List<String> ingredientIds = new ArrayList<>(
                        strategy.getIngredientIds() != null ? strategy.getIngredientIds() : List.of()
                    );
                    if (!ingredientIds.contains(ingredientId)) {
                        ingredientIds.add(ingredientId);
                        strategy.setIngredientIds(ingredientIds);
                        ingredientStrategies.put(ingredientId, strategy);
                    }
                    break;
                }
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    public void removeIngredientFromStrategy(String ingredientId) {
        lock.writeLock().lock();
        try {
            ingredientStrategies.remove(ingredientId);
            for (DeductionStrategy strategy : strategies) {
                if (strategy.getIngredientIds() != null) {
                    List<String> newIds = new ArrayList<>(strategy.getIngredientIds());
                    if (newIds.remove(ingredientId)) {
                        strategy.setIngredientIds(newIds);
                    }
                }
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    public void addCategoryToStrategy(String strategyName, String category) {
        lock.writeLock().lock();
        try {
            for (DeductionStrategy strategy : strategies) {
                if (strategy.getName().equals(strategyName)) {
                    List<String> categories = new ArrayList<>(
                        strategy.getCategories() != null ? strategy.getCategories() : List.of()
                    );
                    if (!categories.contains(category)) {
                        categories.add(category);
                        strategy.setCategories(categories);
                        categoryStrategies.put(category, strategy);
                    }
                    break;
                }
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    public void removeCategoryFromStrategy(String category) {
        lock.writeLock().lock();
        try {
            categoryStrategies.remove(category);
            for (DeductionStrategy strategy : strategies) {
                if (strategy.getCategories() != null) {
                    List<String> newCategories = new ArrayList<>(strategy.getCategories());
                    if (newCategories.remove(category)) {
                        strategy.setCategories(newCategories);
                    }
                }
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    public void addStrategy(DeductionStrategy strategy) {
        lock.writeLock().lock();
        try {
            strategies.add(strategy);
            buildStrategyMaps();
        } finally {
            lock.writeLock().unlock();
        }
    }

    public boolean updateStrategy(String strategyName, DeductionStrategy updatedStrategy) {
        lock.writeLock().lock();
        try {
            for (int i = 0; i < strategies.size(); i++) {
                if (strategies.get(i).getName().equals(strategyName)) {
                    strategies.set(i, updatedStrategy);
                    buildStrategyMaps();
                    return true;
                }
            }
            return false;
        } finally {
            lock.writeLock().unlock();
        }
    }

    public boolean removeStrategy(String strategyName) {
        lock.writeLock().lock();
        try {
            boolean removed = strategies.removeIf(s -> s.getName().equals(strategyName));
            if (removed) {
                buildStrategyMaps();
            }
            return removed;
        } finally {
            lock.writeLock().unlock();
        }
    }

    public double getWarningLevelHighRatio() {
        return warningLevelHighRatio;
    }

    public void setWarningLevelHighRatio(double warningLevelHighRatio) {
        this.warningLevelHighRatio = warningLevelHighRatio;
    }

    public double getWarningLevelMediumRatio() {
        return warningLevelMediumRatio;
    }

    public void setWarningLevelMediumRatio(double warningLevelMediumRatio) {
        this.warningLevelMediumRatio = warningLevelMediumRatio;
    }

    public String getDefaultStrategy() {
        return defaultStrategy;
    }

    public void setDefaultStrategy(String defaultStrategy) {
        this.defaultStrategy = defaultStrategy;
    }

    public static class DeductionStrategy {
        private String name;
        private String strategyType;
        private String description;
        private List<String> ingredientIds;
        private List<String> categories;

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getStrategyType() {
            return strategyType;
        }

        public void setStrategyType(String strategyType) {
            this.strategyType = strategyType;
        }

        public String getDescription() {
            return description;
        }

        public void setDescription(String description) {
            this.description = description;
        }

        public List<String> getIngredientIds() {
            return ingredientIds;
        }

        public void setIngredientIds(List<String> ingredientIds) {
            this.ingredientIds = ingredientIds;
        }

        public List<String> getCategories() {
            return categories;
        }

        public void setCategories(List<String> categories) {
            this.categories = categories;
        }
    }
}
