package com.loganalytics.pipeline.config;

import com.loganalytics.common.config.AppConfig;
import com.loganalytics.common.model.LogLevel;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

public class PipelineConfig {
    private String applicationId;
    private String bootstrapServers;
    private String inputTopic;
    private String parsedTopic;
    private String enrichedTopic;
    private String errorTopic;
    private String anomalyTopic;
    private String archiveTopic;
    private int numStreamThreads;
    private Duration commitInterval;
    private long cacheMaxBytesBuffering;

    private List<String> grokPatterns;
    private String customPatternsDir;
    private List<String> noiseKeywords;
    private LogLevel minLogLevel;
    private boolean debugEnabled;
    private boolean healthCheckExcludeEnabled;

    private String cmdbServiceUrl;
    private long cmdbCacheTtlMinutes;
    private String geoIpDbPath;
    private String traceServiceUrl;

    private String routingRulesConfig;

    public PipelineConfig() {
        this.grokPatterns = new ArrayList<>();
        this.noiseKeywords = new ArrayList<>();
    }

    public static PipelineConfig fromAppConfig(AppConfig config) {
        PipelineConfig pc = new PipelineConfig();

        pc.setApplicationId(config.getString("pipeline.application.id", "log-analytics-pipeline"));
        pc.setBootstrapServers(config.getString("kafka.bootstrap.servers", "localhost:9092"));
        pc.setInputTopic(config.getString("pipeline.input.topic", "raw-logs"));
        pc.setParsedTopic(config.getString("pipeline.parsed.topic", "parsed-logs"));
        pc.setEnrichedTopic(config.getString("pipeline.enriched.topic", "enriched-logs"));
        pc.setErrorTopic(config.getString("pipeline.error.topic", "error-logs"));
        pc.setAnomalyTopic(config.getString("pipeline.anomaly.topic", "anomalies"));
        pc.setArchiveTopic(config.getString("pipeline.archive.topic", "archive-logs"));
        pc.setNumStreamThreads(config.getInt("pipeline.threads", Runtime.getRuntime().availableProcessors()));
        pc.setCommitInterval(Duration.ofMillis(config.getInt("pipeline.commit.interval.ms", 5000)));
        pc.setCacheMaxBytesBuffering(config.getLong("pipeline.cache.max.bytes", 100 * 1024 * 1024L));

        String patterns = config.getString("pipeline.grok.patterns",
                "%{TIMESTAMP_ISO8601:timestamp} %{LOGLEVEL:level} %{DATA:service} %{GREEDYDATA:message}");
        for (String p : patterns.split("\\|")) {
            pc.getGrokPatterns().add(p.trim());
        }

        String noise = config.getString("pipeline.noise.keywords",
                "healthcheck,/actuator/health,/health,metrics,DEBUG TRACE");
        for (String k : noise.split(",")) {
            pc.getNoiseKeywords().add(k.trim().toLowerCase());
        }

        pc.setMinLogLevel(LogLevel.fromString(config.getString("pipeline.min.level", "DEBUG")));
        pc.setDebugEnabled(config.getBoolean("pipeline.debug.enabled", false));
        pc.setHealthCheckExcludeEnabled(config.getBoolean("pipeline.exclude.healthcheck", true));

        pc.setCmdbServiceUrl(config.getString("cmdb.service.url", "http://cmdb:8080"));
        pc.setCmdbCacheTtlMinutes(config.getLong("cmdb.cache.ttl.minutes", 30));
        pc.setGeoIpDbPath(config.getString("geoip.db.path", "/var/lib/geoip/GeoLite2-City.mmdb"));
        pc.setTraceServiceUrl(config.getString("trace.service.url", "http://jaeger:16686"));

        pc.setRoutingRulesConfig(config.getString("pipeline.routing.rules", "default"));

        return pc;
    }

    public String getApplicationId() { return applicationId; }
    public void setApplicationId(String applicationId) { this.applicationId = applicationId; }

    public String getBootstrapServers() { return bootstrapServers; }
    public void setBootstrapServers(String bootstrapServers) { this.bootstrapServers = bootstrapServers; }

    public String getInputTopic() { return inputTopic; }
    public void setInputTopic(String inputTopic) { this.inputTopic = inputTopic; }

    public String getParsedTopic() { return parsedTopic; }
    public void setParsedTopic(String parsedTopic) { this.parsedTopic = parsedTopic; }

    public String getEnrichedTopic() { return enrichedTopic; }
    public void setEnrichedTopic(String enrichedTopic) { this.enrichedTopic = enrichedTopic; }

    public String getErrorTopic() { return errorTopic; }
    public void setErrorTopic(String errorTopic) { this.errorTopic = errorTopic; }

    public String getAnomalyTopic() { return anomalyTopic; }
    public void setAnomalyTopic(String anomalyTopic) { this.anomalyTopic = anomalyTopic; }

    public String getArchiveTopic() { return archiveTopic; }
    public void setArchiveTopic(String archiveTopic) { this.archiveTopic = archiveTopic; }

    public int getNumStreamThreads() { return numStreamThreads; }
    public void setNumStreamThreads(int numStreamThreads) { this.numStreamThreads = numStreamThreads; }

    public Duration getCommitInterval() { return commitInterval; }
    public void setCommitInterval(Duration commitInterval) { this.commitInterval = commitInterval; }

    public long getCacheMaxBytesBuffering() { return cacheMaxBytesBuffering; }
    public void setCacheMaxBytesBuffering(long cacheMaxBytesBuffering) { this.cacheMaxBytesBuffering = cacheMaxBytesBuffering; }

    public List<String> getGrokPatterns() { return grokPatterns; }
    public void setGrokPatterns(List<String> grokPatterns) { this.grokPatterns = grokPatterns; }

    public String getCustomPatternsDir() { return customPatternsDir; }
    public void setCustomPatternsDir(String customPatternsDir) { this.customPatternsDir = customPatternsDir; }

    public List<String> getNoiseKeywords() { return noiseKeywords; }
    public void setNoiseKeywords(List<String> noiseKeywords) { this.noiseKeywords = noiseKeywords; }

    public LogLevel getMinLogLevel() { return minLogLevel; }
    public void setMinLogLevel(LogLevel minLogLevel) { this.minLogLevel = minLogLevel; }

    public boolean isDebugEnabled() { return debugEnabled; }
    public void setDebugEnabled(boolean debugEnabled) { this.debugEnabled = debugEnabled; }

    public boolean isHealthCheckExcludeEnabled() { return healthCheckExcludeEnabled; }
    public void setHealthCheckExcludeEnabled(boolean healthCheckExcludeEnabled) { this.healthCheckExcludeEnabled = healthCheckExcludeEnabled; }

    public String getCmdbServiceUrl() { return cmdbServiceUrl; }
    public void setCmdbServiceUrl(String cmdbServiceUrl) { this.cmdbServiceUrl = cmdbServiceUrl; }

    public long getCmdbCacheTtlMinutes() { return cmdbCacheTtlMinutes; }
    public void setCmdbCacheTtlMinutes(long cmdbCacheTtlMinutes) { this.cmdbCacheTtlMinutes = cmdbCacheTtlMinutes; }

    public String getGeoIpDbPath() { return geoIpDbPath; }
    public void setGeoIpDbPath(String geoIpDbPath) { this.geoIpDbPath = geoIpDbPath; }

    public String getTraceServiceUrl() { return traceServiceUrl; }
    public void setTraceServiceUrl(String traceServiceUrl) { this.traceServiceUrl = traceServiceUrl; }

    public String getRoutingRulesConfig() { return routingRulesConfig; }
    public void setRoutingRulesConfig(String routingRulesConfig) { this.routingRulesConfig = routingRulesConfig; }
}
