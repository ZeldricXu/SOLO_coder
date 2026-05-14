package com.flightmgmt.common.model;

import java.util.HashMap;
import java.util.Map;

public class ChangeRuleConfig {
    private String configId;
    private String configName;
    private Map<String, Double> refundFeeRates;
    private Map<String, Double> rebookFeeRates;
    private int freeCancelHours;
    private int lastMinuteHours;
    private double lastMinuteFeeRate;
    private boolean allowRebooking;
    private int maxRebookCount;
    private boolean enabled;

    public ChangeRuleConfig() {
        this.refundFeeRates = new HashMap<>();
        this.rebookFeeRates = new HashMap<>();
        this.freeCancelHours = 72;
        this.lastMinuteHours = 24;
        this.lastMinuteFeeRate = 0.50;
        this.allowRebooking = true;
        this.maxRebookCount = 2;
        this.enabled = true;
    }

    public static ChangeRuleConfig createDefault() {
        ChangeRuleConfig config = new ChangeRuleConfig();
        config.setConfigId("default_change_rule");
        config.setConfigName("默认退改规则");
        config.refundFeeRates.put("domestic", 0.10);
        config.refundFeeRates.put("international", 0.20);
        config.rebookFeeRates.put("domestic", 0.05);
        config.rebookFeeRates.put("international", 0.10);
        config.setFreeCancelHours(72);
        config.setLastMinuteHours(24);
        config.setLastMinuteFeeRate(0.50);
        config.setAllowRebooking(true);
        config.setMaxRebookCount(2);
        config.setEnabled(true);
        return config;
    }

    public double getRefundFeeRate(String flightType) {
        if (flightType == null) {
            return refundFeeRates.getOrDefault("domestic", 0.10);
        }
        return refundFeeRates.getOrDefault(flightType.toLowerCase(), 0.10);
    }

    public double getRebookFeeRate(String flightType) {
        if (flightType == null) {
            return rebookFeeRates.getOrDefault("domestic", 0.05);
        }
        return rebookFeeRates.getOrDefault(flightType.toLowerCase(), 0.05);
    }

    public String getConfigId() { return configId; }
    public void setConfigId(String configId) { this.configId = configId; }
    public String getConfigName() { return configName; }
    public void setConfigName(String configName) { this.configName = configName; }
    public Map<String, Double> getRefundFeeRates() { return refundFeeRates; }
    public void setRefundFeeRates(Map<String, Double> refundFeeRates) { this.refundFeeRates = refundFeeRates; }
    public Map<String, Double> getRebookFeeRates() { return rebookFeeRates; }
    public void setRebookFeeRates(Map<String, Double> rebookFeeRates) { this.rebookFeeRates = rebookFeeRates; }
    public int getFreeCancelHours() { return freeCancelHours; }
    public void setFreeCancelHours(int freeCancelHours) { this.freeCancelHours = freeCancelHours; }
    public int getLastMinuteHours() { return lastMinuteHours; }
    public void setLastMinuteHours(int lastMinuteHours) { this.lastMinuteHours = lastMinuteHours; }
    public double getLastMinuteFeeRate() { return lastMinuteFeeRate; }
    public void setLastMinuteFeeRate(double lastMinuteFeeRate) { this.lastMinuteFeeRate = lastMinuteFeeRate; }
    public boolean isAllowRebooking() { return allowRebooking; }
    public void setAllowRebooking(boolean allowRebooking) { this.allowRebooking = allowRebooking; }
    public int getMaxRebookCount() { return maxRebookCount; }
    public void setMaxRebookCount(int maxRebookCount) { this.maxRebookCount = maxRebookCount; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
}
