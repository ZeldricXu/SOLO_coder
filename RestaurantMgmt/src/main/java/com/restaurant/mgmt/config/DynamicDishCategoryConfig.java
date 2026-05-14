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
@ConfigurationProperties(prefix = "restaurant.dish.category")
public class DynamicDishCategoryConfig {

    private List<DishCategory> categories = new ArrayList<>();
    private Map<String, DishCategory> categoryMap = new HashMap<>();
    
    private final ReadWriteLock lock = new ReentrantReadWriteLock();

    @PostConstruct
    public void initDefaultCategories() {
        if (categories == null || categories.isEmpty()) {
            categories = new ArrayList<>();
            
            DishCategory main = new DishCategory();
            main.setCode("main");
            main.setName("主菜");
            main.setDescription("招牌主菜、特色菜品");
            main.setSortOrder(1);
            main.setEnabled(true);
            main.setIcon("🍽️");
            categories.add(main);
            
            DishCategory appetizer = new DishCategory();
            appetizer.setCode("appetizer");
            appetizer.setName("凉菜");
            appetizer.setDescription("开胃小菜、凉拌菜");
            appetizer.setSortOrder(2);
            appetizer.setEnabled(true);
            appetizer.setIcon("🥗");
            categories.add(appetizer);
            
            DishCategory soup = new DishCategory();
            soup.setCode("soup");
            soup.setName("汤品");
            soup.setDescription("各类汤品、炖汤");
            soup.setSortOrder(3);
            soup.setEnabled(true);
            soup.setIcon("🍲");
            categories.add(soup);
            
            DishCategory drink = new DishCategory();
            drink.setCode("drink");
            drink.setName("饮品");
            drink.setDescription("饮料、酒水、果汁");
            drink.setSortOrder(4);
            drink.setEnabled(true);
            drink.setIcon("🥤");
            categories.add(drink);
            
            DishCategory staple = new DishCategory();
            staple.setCode("staple");
            staple.setName("主食");
            staple.setDescription("米饭、面食、点心");
            staple.setSortOrder(5);
            staple.setEnabled(true);
            staple.setIcon("🍚");
            categories.add(staple);
            
            DishCategory dessert = new DishCategory();
            dessert.setCode("dessert");
            dessert.setName("甜点");
            dessert.setDescription("餐后甜点、水果");
            dessert.setSortOrder(6);
            dessert.setEnabled(true);
            dessert.setIcon("🍰");
            categories.add(dessert);
        }
        buildCategoryMap();
    }

    private void buildCategoryMap() {
        lock.writeLock().lock();
        try {
            categoryMap = new HashMap<>();
            for (DishCategory category : categories) {
                categoryMap.put(category.getCode(), category);
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    public void setCategories(List<DishCategory> categories) {
        lock.writeLock().lock();
        try {
            this.categories = new ArrayList<>(categories);
            buildCategoryMap();
        } finally {
            lock.writeLock().unlock();
        }
    }

    public List<DishCategory> getCategories() {
        lock.readLock().lock();
        try {
            List<DishCategory> result = new ArrayList<>();
            for (DishCategory category : categories) {
                if (category.isEnabled()) {
                    result.add(category);
                }
            }
            result.sort((a, b) -> Integer.compare(a.getSortOrder(), b.getSortOrder()));
            return result;
        } finally {
            lock.readLock().unlock();
        }
    }

    public List<DishCategory> getAllCategories() {
        lock.readLock().lock();
        try {
            List<DishCategory> result = new ArrayList<>(categories);
            result.sort((a, b) -> Integer.compare(a.getSortOrder(), b.getSortOrder()));
            return result;
        } finally {
            lock.readLock().unlock();
        }
    }

    public DishCategory getCategory(String code) {
        lock.readLock().lock();
        try {
            return categoryMap.get(code);
        } finally {
            lock.readLock().unlock();
        }
    }

    public boolean isValidCategory(String code) {
        lock.readLock().lock();
        try {
            DishCategory category = categoryMap.get(code);
            return category != null && category.isEnabled();
        } finally {
            lock.readLock().unlock();
        }
    }

    public String getCategoryName(String code) {
        lock.readLock().lock();
        try {
            DishCategory category = categoryMap.get(code);
            return category != null ? category.getName() : code;
        } finally {
            lock.readLock().unlock();
        }
    }

    public void addCategory(DishCategory category) {
        lock.writeLock().lock();
        try {
            categories.add(category);
            buildCategoryMap();
        } finally {
            lock.writeLock().unlock();
        }
    }

    public boolean updateCategory(String code, DishCategory updatedCategory) {
        lock.writeLock().lock();
        try {
            for (int i = 0; i < categories.size(); i++) {
                if (categories.get(i).getCode().equals(code)) {
                    updatedCategory.setCode(code);
                    categories.set(i, updatedCategory);
                    buildCategoryMap();
                    return true;
                }
            }
            return false;
        } finally {
            lock.writeLock().unlock();
        }
    }

    public boolean removeCategory(String code) {
        lock.writeLock().lock();
        try {
            boolean removed = categories.removeIf(c -> c.getCode().equals(code));
            if (removed) {
                buildCategoryMap();
            }
            return removed;
        } finally {
            lock.writeLock().unlock();
        }
    }

    public boolean enableCategory(String code) {
        lock.writeLock().lock();
        try {
            DishCategory category = categoryMap.get(code);
            if (category != null) {
                category.setEnabled(true);
                return true;
            }
            return false;
        } finally {
            lock.writeLock().unlock();
        }
    }

    public boolean disableCategory(String code) {
        lock.writeLock().lock();
        try {
            DishCategory category = categoryMap.get(code);
            if (category != null) {
                category.setEnabled(false);
                return true;
            }
            return false;
        } finally {
            lock.writeLock().unlock();
        }
    }

    public static class DishCategory {
        private String code;
        private String name;
        private String description;
        private int sortOrder;
        private boolean enabled;
        private String icon;

        public String getCode() {
            return code;
        }

        public void setCode(String code) {
            this.code = code;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getDescription() {
            return description;
        }

        public void setDescription(String description) {
            this.description = description;
        }

        public int getSortOrder() {
            return sortOrder;
        }

        public void setSortOrder(int sortOrder) {
            this.sortOrder = sortOrder;
        }

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getIcon() {
            return icon;
        }

        public void setIcon(String icon) {
            this.icon = icon;
        }
    }
}
