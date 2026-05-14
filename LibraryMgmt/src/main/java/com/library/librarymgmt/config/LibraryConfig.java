package com.library.librarymgmt.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Data
@Component
@ConfigurationProperties(prefix = "library")
public class LibraryConfig {
    private Borrow borrow = new Borrow();
    private Reader reader = new Reader();
    private Lock lock = new Lock();
    private Reminder reminder = new Reminder();
    private Notification notification = new Notification();
    private Category category = new Category();

    @Data
    public static class Borrow {
        private int defaultDays = 15;
    }

    @Data
    public static class Reader {
        private int defaultBorrowLimit = 5;
    }

    @Data
    public static class Lock {
        private Map<String, Integer> timeout = new HashMap<>();

        public int getTimeoutByReaderType(String readerType) {
            return timeout.getOrDefault(readerType, 120);
        }
    }

    @Data
    public static class Reminder {
        private Map<String, Integer> daysBeforeDue = new HashMap<>();

        public int getDaysBeforeDueByCategory(String bookCategory) {
            return daysBeforeDue.getOrDefault(bookCategory, 3);
        }
    }

    @Data
    public static class Notification {
        private RedisQueue redisQueue = new RedisQueue();
        private int maxRetries = 3;
        private int retryDelaySeconds = 60;

        @Data
        public static class RedisQueue {
            private String queueKey = "library:reservation:notifications";
            private String processingKey = "library:reservation:processing";
            private int pollIntervalMs = 1000;
            private int maxWorkers = 3;
        }
    }

    @Data
    public static class Category {
        private List<String> available = new ArrayList<>();
        private Map<String, CategoryConfig> config = new HashMap<>();

        public boolean isCategoryValid(String categoryName) {
            return available.contains(categoryName);
        }

        public CategoryConfig getCategoryConfig(String categoryName) {
            return config.get(categoryName);
        }

        @Data
        public static class CategoryConfig {
            private String name;
            private String description;
            private String reminderPolicy = "default";
            private int maxBorrowDays = 15;
            private boolean enabled = true;
        }
    }
}
