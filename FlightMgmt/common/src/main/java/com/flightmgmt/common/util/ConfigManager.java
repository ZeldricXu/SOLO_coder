package com.flightmgmt.common.util;

import com.flightmgmt.common.model.ChangeRuleConfig;
import com.flightmgmt.common.model.PaymentTimeoutConfig;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class ConfigManager {
    private static ConfigManager instance;
    private final Map<String, PaymentTimeoutConfig> paymentTimeoutConfigs;
    private final Map<String, ChangeRuleConfig> changeRuleConfigs;
    private volatile PaymentTimeoutConfig activePaymentTimeoutConfig;
    private volatile ChangeRuleConfig activeChangeRuleConfig;

    private ConfigManager() {
        this.paymentTimeoutConfigs = new ConcurrentHashMap<>();
        this.changeRuleConfigs = new ConcurrentHashMap<>();
        initializeDefaultConfigs();
    }

    public static synchronized ConfigManager getInstance() {
        if (instance == null) {
            instance = new ConfigManager();
        }
        return instance;
    }

    private void initializeDefaultConfigs() {
        PaymentTimeoutConfig defaultTimeoutConfig = PaymentTimeoutConfig.createDefault();
        this.paymentTimeoutConfigs.put(defaultTimeoutConfig.getConfigId(), defaultTimeoutConfig);
        this.activePaymentTimeoutConfig = defaultTimeoutConfig;

        ChangeRuleConfig defaultChangeConfig = ChangeRuleConfig.createDefault();
        this.changeRuleConfigs.put(defaultChangeConfig.getConfigId(), defaultChangeConfig);
        this.activeChangeRuleConfig = defaultChangeConfig;
    }

    public PaymentTimeoutConfig getPaymentTimeoutConfig() {
        return activePaymentTimeoutConfig;
    }

    public void setActivePaymentTimeoutConfig(String configId) {
        PaymentTimeoutConfig config = paymentTimeoutConfigs.get(configId);
        if (config != null) {
            this.activePaymentTimeoutConfig = config;
        }
    }

    public void addPaymentTimeoutConfig(PaymentTimeoutConfig config) {
        if (config.getConfigId() == null || config.getConfigId().isEmpty()) {
            config.setConfigId("payment_timeout_" + System.currentTimeMillis());
        }
        this.paymentTimeoutConfigs.put(config.getConfigId(), config);
    }

    public void updatePaymentTimeoutConfig(PaymentTimeoutConfig config) {
        if (paymentTimeoutConfigs.containsKey(config.getConfigId())) {
            this.paymentTimeoutConfigs.put(config.getConfigId(), config);
            if (activePaymentTimeoutConfig != null && 
                activePaymentTimeoutConfig.getConfigId().equals(config.getConfigId())) {
                this.activePaymentTimeoutConfig = config;
            }
        }
    }

    public int getPaymentTimeoutMinutes(String flightType) {
        if (activePaymentTimeoutConfig == null) {
            return 15;
        }
        return activePaymentTimeoutConfig.getTimeoutMinutes(flightType);
    }

    public ChangeRuleConfig getChangeRuleConfig() {
        return activeChangeRuleConfig;
    }

    public void setActiveChangeRuleConfig(String configId) {
        ChangeRuleConfig config = changeRuleConfigs.get(configId);
        if (config != null) {
            this.activeChangeRuleConfig = config;
        }
    }

    public void addChangeRuleConfig(ChangeRuleConfig config) {
        if (config.getConfigId() == null || config.getConfigId().isEmpty()) {
            config.setConfigId("change_rule_" + System.currentTimeMillis());
        }
        this.changeRuleConfigs.put(config.getConfigId(), config);
    }

    public void updateChangeRuleConfig(ChangeRuleConfig config) {
        if (changeRuleConfigs.containsKey(config.getConfigId())) {
            this.changeRuleConfigs.put(config.getConfigId(), config);
            if (activeChangeRuleConfig != null && 
                activeChangeRuleConfig.getConfigId().equals(config.getConfigId())) {
                this.activeChangeRuleConfig = config;
            }
        }
    }

    public double getRefundFeeRate(String flightType) {
        if (activeChangeRuleConfig == null) {
            return 0.10;
        }
        return activeChangeRuleConfig.getRefundFeeRate(flightType);
    }

    public double getRebookFeeRate(String flightType) {
        if (activeChangeRuleConfig == null) {
            return 0.05;
        }
        return activeChangeRuleConfig.getRebookFeeRate(flightType);
    }

    public int getFreeCancelHours() {
        if (activeChangeRuleConfig == null) {
            return 72;
        }
        return activeChangeRuleConfig.getFreeCancelHours();
    }

    public int getLastMinuteHours() {
        if (activeChangeRuleConfig == null) {
            return 24;
        }
        return activeChangeRuleConfig.getLastMinuteHours();
    }

    public double getLastMinuteFeeRate() {
        if (activeChangeRuleConfig == null) {
            return 0.50;
        }
        return activeChangeRuleConfig.getLastMinuteFeeRate();
    }

    public boolean isRebookAllowed() {
        if (activeChangeRuleConfig == null) {
            return true;
        }
        return activeChangeRuleConfig.isAllowRebooking();
    }

    public int getMaxRebookCount() {
        if (activeChangeRuleConfig == null) {
            return 2;
        }
        return activeChangeRuleConfig.getMaxRebookCount();
    }

    public Map<String, PaymentTimeoutConfig> getAllPaymentTimeoutConfigs() {
        return new ConcurrentHashMap<>(paymentTimeoutConfigs);
    }

    public Map<String, ChangeRuleConfig> getAllChangeRuleConfigs() {
        return new ConcurrentHashMap<>(changeRuleConfigs);
    }

    public void reloadConfigs() {
        initializeDefaultConfigs();
    }
}
