package com.healthtrack.dto;

import java.util.List;

public class HealthIndicatorsResponse {
    private List<IndicatorInfo> indicators;

    public HealthIndicatorsResponse() {}

    public HealthIndicatorsResponse(List<IndicatorInfo> indicators) {
        this.indicators = indicators;
    }

    public List<IndicatorInfo> getIndicators() { return indicators; }
    public void setIndicators(List<IndicatorInfo> indicators) { this.indicators = indicators; }

    public static class IndicatorInfo {
        private String indicatorType;
        private Double currentValue;
        private Double averageValue;
        private String trend;
        private String status;

        public IndicatorInfo() {}

        public IndicatorInfo(String indicatorType, Double currentValue, Double averageValue, String trend, String status) {
            this.indicatorType = indicatorType;
            this.currentValue = currentValue;
            this.averageValue = averageValue;
            this.trend = trend;
            this.status = status;
        }

        public String getIndicatorType() { return indicatorType; }
        public void setIndicatorType(String indicatorType) { this.indicatorType = indicatorType; }
        public Double getCurrentValue() { return currentValue; }
        public void setCurrentValue(Double currentValue) { this.currentValue = currentValue; }
        public Double getAverageValue() { return averageValue; }
        public void setAverageValue(Double averageValue) { this.averageValue = averageValue; }
        public String getTrend() { return trend; }
        public void setTrend(String trend) { this.trend = trend; }
        public String getStatus() { return status; }
        public void setStatus(String status) { this.status = status; }
    }
}
