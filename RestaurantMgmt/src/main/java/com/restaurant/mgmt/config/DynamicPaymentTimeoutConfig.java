package com.restaurant.mgmt.config;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

@Component
@ConfigurationProperties(prefix = "restaurant.payment.timeout")
public class DynamicPaymentTimeoutConfig {

    private List<TimeoutTier> tiers = new ArrayList<>();
    
    private final ReadWriteLock lock = new ReentrantReadWriteLock();

    @Value("${restaurant.payment.timeout.default-timeout:10}")
    private int defaultTimeoutMinutes;

    @Value("${restaurant.payment.timeout.default-reminder:7}")
    private int defaultReminderMinutes;

    @PostConstruct
    public void initDefaultTiers() {
        if (tiers == null || tiers.isEmpty()) {
            tiers = new ArrayList<>();
            
            TimeoutTier smallTier = new TimeoutTier();
            smallTier.setName("small");
            smallTier.setMinAmount(0.0);
            smallTier.setMaxAmount(100.0);
            smallTier.setTimeoutMinutes(5);
            smallTier.setReminderMinutes(3);
            smallTier.setDescription("小额订单-快速处理");
            tiers.add(smallTier);
            
            TimeoutTier mediumTier = new TimeoutTier();
            mediumTier.setName("medium");
            mediumTier.setMinAmount(100.0);
            mediumTier.setMaxAmount(500.0);
            mediumTier.setTimeoutMinutes(10);
            mediumTier.setReminderMinutes(7);
            mediumTier.setDescription("中额订单-标准处理");
            tiers.add(mediumTier);
            
            TimeoutTier largeTier = new TimeoutTier();
            largeTier.setName("large");
            largeTier.setMinAmount(500.0);
            largeTier.setMaxAmount(Double.MAX_VALUE);
            largeTier.setTimeoutMinutes(20);
            largeTier.setReminderMinutes(15);
            largeTier.setDescription("大额订单-顾客确认");
            tiers.add(largeTier);
        }
        sortTiers();
    }

    public void setTiers(List<TimeoutTier> tiers) {
        lock.writeLock().lock();
        try {
            this.tiers = new ArrayList<>(tiers);
            sortTiers();
        } finally {
            lock.writeLock().unlock();
        }
    }

    public List<TimeoutTier> getTiers() {
        lock.readLock().lock();
        try {
            return new ArrayList<>(tiers);
        } finally {
            lock.readLock().unlock();
        }
    }

    public int getTimeoutMinutes(double orderAmount) {
        lock.readLock().lock();
        try {
            TimeoutTier tier = findTier(orderAmount);
            return tier != null ? tier.getTimeoutMinutes() : defaultTimeoutMinutes;
        } finally {
            lock.readLock().unlock();
        }
    }

    public int getReminderMinutes(double orderAmount) {
        lock.readLock().lock();
        try {
            TimeoutTier tier = findTier(orderAmount);
            return tier != null ? tier.getReminderMinutes() : defaultReminderMinutes;
        } finally {
            lock.readLock().unlock();
        }
    }

    public String getOrderSizeCategory(double orderAmount) {
        lock.readLock().lock();
        try {
            TimeoutTier tier = findTier(orderAmount);
            return tier != null ? tier.getName() : "medium";
        } finally {
            lock.readLock().unlock();
        }
    }

    public boolean isSmallOrder(double orderAmount) {
        return "small".equals(getOrderSizeCategory(orderAmount));
    }

    public boolean isLargeOrder(double orderAmount) {
        return "large".equals(getOrderSizeCategory(orderAmount));
    }

    public TimeoutTier findTier(double orderAmount) {
        lock.readLock().lock();
        try {
            for (TimeoutTier tier : tiers) {
                if (orderAmount >= tier.getMinAmount() && orderAmount < tier.getMaxAmount()) {
                    return tier;
                }
            }
            return null;
        } finally {
            lock.readLock().unlock();
        }
    }

    public void addTier(TimeoutTier tier) {
        lock.writeLock().lock();
        try {
            tiers.add(tier);
            sortTiers();
        } finally {
            lock.writeLock().unlock();
        }
    }

    public boolean updateTier(String tierName, TimeoutTier updatedTier) {
        lock.writeLock().lock();
        try {
            for (int i = 0; i < tiers.size(); i++) {
                if (tiers.get(i).getName().equals(tierName)) {
                    tiers.set(i, updatedTier);
                    sortTiers();
                    return true;
                }
            }
            return false;
        } finally {
            lock.writeLock().unlock();
        }
    }

    public boolean removeTier(String tierName) {
        lock.writeLock().lock();
        try {
            return tiers.removeIf(tier -> tier.getName().equals(tierName));
        } finally {
            lock.writeLock().unlock();
        }
    }

    private void sortTiers() {
        tiers.sort(Comparator.comparingDouble(TimeoutTier::getMinAmount));
    }

    public double getSmallOrderThreshold() {
        lock.readLock().lock();
        try {
            for (TimeoutTier tier : tiers) {
                if ("small".equals(tier.getName())) {
                    return tier.getMaxAmount();
                }
            }
            return 100.0;
        } finally {
            lock.readLock().unlock();
        }
    }

    public double getLargeOrderThreshold() {
        lock.readLock().lock();
        try {
            for (TimeoutTier tier : tiers) {
                if ("large".equals(tier.getName())) {
                    return tier.getMinAmount();
                }
            }
            return 500.0;
        } finally {
            lock.readLock().unlock();
        }
    }

    public int getDefaultTimeoutMinutes() {
        return defaultTimeoutMinutes;
    }

    public int getDefaultReminderMinutes() {
        return defaultReminderMinutes;
    }

    public static class TimeoutTier {
        private String name;
        private double minAmount;
        private double maxAmount;
        private int timeoutMinutes;
        private int reminderMinutes;
        private String description;

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public double getMinAmount() {
            return minAmount;
        }

        public void setMinAmount(double minAmount) {
            this.minAmount = minAmount;
        }

        public double getMaxAmount() {
            return maxAmount;
        }

        public void setMaxAmount(double maxAmount) {
            this.maxAmount = maxAmount;
        }

        public int getTimeoutMinutes() {
            return timeoutMinutes;
        }

        public void setTimeoutMinutes(int timeoutMinutes) {
            this.timeoutMinutes = timeoutMinutes;
        }

        public int getReminderMinutes() {
            return reminderMinutes;
        }

        public void setReminderMinutes(int reminderMinutes) {
            this.reminderMinutes = reminderMinutes;
        }

        public String getDescription() {
            return description;
        }

        public void setDescription(String description) {
            this.description = description;
        }
    }
}
