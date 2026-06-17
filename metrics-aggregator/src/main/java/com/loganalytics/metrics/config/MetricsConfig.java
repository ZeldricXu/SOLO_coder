package com.loganalytics.metrics.config;

import com.loganalytics.common.config.AppConfig;

import java.time.Duration;
import java.util.List;

public class MetricsConfig {
    private String applicationId;
    private String bootstrapServers;
    private String inputTopic;
    private String outputTopic;

    private List<WindowConfig> windows;

    private int topKSize;
    private Duration topKUpdateInterval;

    private String timescaleUrl;
    private String timescaleUser;
    private String timescalePassword;
    private int timescalePoolSize;

    private int rawDataRetentionDays;
    private int minuteAggRetentionDays;
    private int hourAggRetentionDays;

    private boolean enableContinuousAggregation;
    private int aggregationParallelism;

    private String chunkTimeInterval;
    private int batchFlushIntervalSeconds;
    private int batchSize;
    private int maxRetryAttempts;

    public static class WindowConfig {
        private final String name;
        private final Duration size;
        private final Duration advance;
        private final WindowType type;

        public enum WindowType {
            TUMBLING, HOPPING, SESSION
        }

        public WindowConfig(String name, Duration size, Duration advance, WindowType type) {
            this.name = name;
            this.size = size;
            this.advance = advance;
            this.type = type;
        }

        public String getName() { return name; }
        public Duration getSize() { return size; }
        public Duration getAdvance() { return advance; }
        public WindowType getType() { return type; }
    }

    public MetricsConfig() {}

    public static MetricsConfig fromAppConfig(AppConfig config) {
        MetricsConfig mc = new MetricsConfig();

        mc.setApplicationId(config.getString("metrics.application.id", "metrics-aggregator"));
        mc.setBootstrapServers(config.getString("kafka.bootstrap.servers", "localhost:9092"));
        mc.setInputTopic(config.getString("metrics.input.topic", "enriched-logs"));
        mc.setOutputTopic(config.getString("metrics.output.topic", "metrics"));

        mc.setWindows(List.of(
                new WindowConfig("1min_tumbling", Duration.ofMinutes(1), Duration.ofMinutes(1), WindowConfig.WindowType.TUMBLING),
                new WindowConfig("5min_hopping", Duration.ofMinutes(5), Duration.ofMinutes(1), WindowConfig.WindowType.HOPPING),
                new WindowConfig("1h_session", Duration.ofHours(1), null, WindowConfig.WindowType.SESSION)
        ));

        mc.setTopKSize(config.getInt("metrics.topk.size", 20));
        mc.setTopKUpdateInterval(Duration.ofSeconds(config.getInt("metrics.topk.interval.seconds", 30)));

        mc.setTimescaleUrl(config.getString("timescale.url", "jdbc:postgresql://localhost:5432/loganalytics"));
        mc.setTimescaleUser(config.getString("timescale.user", "postgres"));
        mc.setTimescalePassword(config.getString("timescale.password", "postgres"));
        mc.setTimescalePoolSize(config.getInt("timescale.pool.size", 10));

        mc.setRawDataRetentionDays(config.getInt("metrics.retention.raw.days", 7));
        mc.setMinuteAggRetentionDays(config.getInt("metrics.retention.minute.days", 30));
        mc.setHourAggRetentionDays(config.getInt("metrics.retention.hour.days", Integer.MAX_VALUE));

        mc.setEnableContinuousAggregation(config.getBoolean("metrics.continuous.aggregation.enabled", true));
        mc.setAggregationParallelism(config.getInt("metrics.parallelism", 4));

        mc.setChunkTimeInterval(config.getString("timescale.chunk.time.interval", "1 hour"));
        mc.setBatchFlushIntervalSeconds(config.getInt("timescale.batch.flush.interval.seconds", 5));
        mc.setBatchSize(config.getInt("timescale.batch.size", 1000));
        mc.setMaxRetryAttempts(config.getInt("timescale.max.retry.attempts", 3));

        return mc;
    }

    public String getApplicationId() { return applicationId; }
    public void setApplicationId(String applicationId) { this.applicationId = applicationId; }

    public String getBootstrapServers() { return bootstrapServers; }
    public void setBootstrapServers(String bootstrapServers) { this.bootstrapServers = bootstrapServers; }

    public String getInputTopic() { return inputTopic; }
    public void setInputTopic(String inputTopic) { this.inputTopic = inputTopic; }

    public String getOutputTopic() { return outputTopic; }
    public void setOutputTopic(String outputTopic) { this.outputTopic = outputTopic; }

    public List<WindowConfig> getWindows() { return windows; }
    public void setWindows(List<WindowConfig> windows) { this.windows = windows; }

    public int getTopKSize() { return topKSize; }
    public void setTopKSize(int topKSize) { this.topKSize = topKSize; }

    public Duration getTopKUpdateInterval() { return topKUpdateInterval; }
    public void setTopKUpdateInterval(Duration topKUpdateInterval) { this.topKUpdateInterval = topKUpdateInterval; }

    public String getTimescaleUrl() { return timescaleUrl; }
    public void setTimescaleUrl(String timescaleUrl) { this.timescaleUrl = timescaleUrl; }

    public String getTimescaleUser() { return timescaleUser; }
    public void setTimescaleUser(String timescaleUser) { this.timescaleUser = timescaleUser; }

    public String getTimescalePassword() { return timescalePassword; }
    public void setTimescalePassword(String timescalePassword) { this.timescalePassword = timescalePassword; }

    public int getTimescalePoolSize() { return timescalePoolSize; }
    public void setTimescalePoolSize(int timescalePoolSize) { this.timescalePoolSize = timescalePoolSize; }

    public int getRawDataRetentionDays() { return rawDataRetentionDays; }
    public void setRawDataRetentionDays(int rawDataRetentionDays) { this.rawDataRetentionDays = rawDataRetentionDays; }

    public int getMinuteAggRetentionDays() { return minuteAggRetentionDays; }
    public void setMinuteAggRetentionDays(int minuteAggRetentionDays) { this.minuteAggRetentionDays = minuteAggRetentionDays; }

    public int getHourAggRetentionDays() { return hourAggRetentionDays; }
    public void setHourAggRetentionDays(int hourAggRetentionDays) { this.hourAggRetentionDays = hourAggRetentionDays; }

    public boolean isEnableContinuousAggregation() { return enableContinuousAggregation; }
    public void setEnableContinuousAggregation(boolean enableContinuousAggregation) { this.enableContinuousAggregation = enableContinuousAggregation; }

    public int getAggregationParallelism() { return aggregationParallelism; }
    public void setAggregationParallelism(int aggregationParallelism) { this.aggregationParallelism = aggregationParallelism; }

    public String getChunkTimeInterval() { return chunkTimeInterval; }
    public void setChunkTimeInterval(String chunkTimeInterval) { this.chunkTimeInterval = chunkTimeInterval; }

    public int getBatchFlushIntervalSeconds() { return batchFlushIntervalSeconds; }
    public void setBatchFlushIntervalSeconds(int batchFlushIntervalSeconds) { this.batchFlushIntervalSeconds = batchFlushIntervalSeconds; }

    public int getBatchSize() { return batchSize; }
    public void setBatchSize(int batchSize) { this.batchSize = batchSize; }

    public int getMaxRetryAttempts() { return maxRetryAttempts; }
    public void setMaxRetryAttempts(int maxRetryAttempts) { this.maxRetryAttempts = maxRetryAttempts; }
}
