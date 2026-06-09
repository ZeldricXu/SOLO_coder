package com.loganalytics.agent.config;

import com.loganalytics.common.config.AppConfig;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

public class AgentConfig {
    private String serviceName;
    private String hostname;
    private String sourceIp;
    private List<String> logPaths;
    private List<String> includePatterns;
    private List<String> excludePatterns;
    private Duration fileDiscoveryInterval;
    private Duration tailPollInterval;
    private int maxLineBytes;
    private boolean multiLineEnabled;
    private String multiLinePattern;
    private boolean multiLineNegate;
    private String multiLineMatch;
    private Duration flushInterval;
    private int batchSize;
    private String kafkaBootstrapServers;
    private String kafkaTopic;
    private int kafkaPartitions;
    private String offsetStorePath;
    private int socketPort;
    private boolean stdoutCaptureEnabled;
    private long stdoutPid;

    public AgentConfig() {
        this.logPaths = new ArrayList<>();
        this.includePatterns = new ArrayList<>();
        this.excludePatterns = new ArrayList<>();
    }

    public static AgentConfig fromAppConfig(AppConfig config) {
        AgentConfig agentConfig = new AgentConfig();
        agentConfig.setServiceName(config.getString("agent.service.name", "unknown-service"));
        agentConfig.setHostname(config.getString("agent.hostname", System.getenv("HOSTNAME") != null ? System.getenv("HOSTNAME") : "localhost"));
        agentConfig.setSourceIp(config.getString("agent.source.ip", "127.0.0.1"));

        String paths = config.getString("agent.log.paths", "/var/log/**/*.log");
        for (String path : paths.split(",")) {
            agentConfig.getLogPaths().add(path.trim());
        }

        agentConfig.setFileDiscoveryInterval(Duration.ofSeconds(config.getInt("agent.discovery.interval.seconds", 30)));
        agentConfig.setTailPollInterval(Duration.ofMillis(config.getInt("agent.tail.poll.interval.ms", 100)));
        agentConfig.setMaxLineBytes(config.getInt("agent.max.line.bytes", 1024 * 1024));
        agentConfig.setMultiLineEnabled(config.getBoolean("agent.multiline.enabled", true));
        agentConfig.setMultiLinePattern(config.getString("agent.multiline.pattern", "^\\s+|^Caused by:|^\\tat "));
        agentConfig.setMultiLineNegate(config.getBoolean("agent.multiline.negate", false));
        agentConfig.setMultiLineMatch(config.getString("agent.multiline.match", "after"));
        agentConfig.setFlushInterval(Duration.ofMillis(config.getInt("agent.flush.interval.ms", 1000)));
        agentConfig.setBatchSize(config.getInt("agent.batch.size", 1000));
        agentConfig.setKafkaBootstrapServers(config.getString("kafka.bootstrap.servers", "localhost:9092"));
        agentConfig.setKafkaTopic(config.getString("kafka.topic", "raw-logs"));
        agentConfig.setKafkaPartitions(config.getInt("kafka.partitions", 12));
        agentConfig.setOffsetStorePath(config.getString("agent.offset.store.path", "/var/lib/log-agent/offsets.json"));
        agentConfig.setSocketPort(config.getInt("agent.socket.port", 5044));
        agentConfig.setStdoutCaptureEnabled(config.getBoolean("agent.stdout.capture.enabled", false));
        agentConfig.setStdoutPid(config.getLong("agent.stdout.pid", -1));

        return agentConfig;
    }

    public String getServiceName() { return serviceName; }
    public void setServiceName(String serviceName) { this.serviceName = serviceName; }

    public String getHostname() { return hostname; }
    public void setHostname(String hostname) { this.hostname = hostname; }

    public String getSourceIp() { return sourceIp; }
    public void setSourceIp(String sourceIp) { this.sourceIp = sourceIp; }

    public List<String> getLogPaths() { return logPaths; }
    public void setLogPaths(List<String> logPaths) { this.logPaths = logPaths; }

    public List<String> getIncludePatterns() { return includePatterns; }
    public void setIncludePatterns(List<String> includePatterns) { this.includePatterns = includePatterns; }

    public List<String> getExcludePatterns() { return excludePatterns; }
    public void setExcludePatterns(List<String> excludePatterns) { this.excludePatterns = excludePatterns; }

    public Duration getFileDiscoveryInterval() { return fileDiscoveryInterval; }
    public void setFileDiscoveryInterval(Duration fileDiscoveryInterval) { this.fileDiscoveryInterval = fileDiscoveryInterval; }

    public Duration getTailPollInterval() { return tailPollInterval; }
    public void setTailPollInterval(Duration tailPollInterval) { this.tailPollInterval = tailPollInterval; }

    public int getMaxLineBytes() { return maxLineBytes; }
    public void setMaxLineBytes(int maxLineBytes) { this.maxLineBytes = maxLineBytes; }

    public boolean isMultiLineEnabled() { return multiLineEnabled; }
    public void setMultiLineEnabled(boolean multiLineEnabled) { this.multiLineEnabled = multiLineEnabled; }

    public String getMultiLinePattern() { return multiLinePattern; }
    public void setMultiLinePattern(String multiLinePattern) { this.multiLinePattern = multiLinePattern; }

    public boolean isMultiLineNegate() { return multiLineNegate; }
    public void setMultiLineNegate(boolean multiLineNegate) { this.multiLineNegate = multiLineNegate; }

    public String getMultiLineMatch() { return multiLineMatch; }
    public void setMultiLineMatch(String multiLineMatch) { this.multiLineMatch = multiLineMatch; }

    public Duration getFlushInterval() { return flushInterval; }
    public void setFlushInterval(Duration flushInterval) { this.flushInterval = flushInterval; }

    public int getBatchSize() { return batchSize; }
    public void setBatchSize(int batchSize) { this.batchSize = batchSize; }

    public String getKafkaBootstrapServers() { return kafkaBootstrapServers; }
    public void setKafkaBootstrapServers(String kafkaBootstrapServers) { this.kafkaBootstrapServers = kafkaBootstrapServers; }

    public String getKafkaTopic() { return kafkaTopic; }
    public void setKafkaTopic(String kafkaTopic) { this.kafkaTopic = kafkaTopic; }

    public int getKafkaPartitions() { return kafkaPartitions; }
    public void setKafkaPartitions(int kafkaPartitions) { this.kafkaPartitions = kafkaPartitions; }

    public String getOffsetStorePath() { return offsetStorePath; }
    public void setOffsetStorePath(String offsetStorePath) { this.offsetStorePath = offsetStorePath; }

    public int getSocketPort() { return socketPort; }
    public void setSocketPort(int socketPort) { this.socketPort = socketPort; }

    public boolean isStdoutCaptureEnabled() { return stdoutCaptureEnabled; }
    public void setStdoutCaptureEnabled(boolean stdoutCaptureEnabled) { this.stdoutCaptureEnabled = stdoutCaptureEnabled; }

    public long getStdoutPid() { return stdoutPid; }
    public void setStdoutPid(long stdoutPid) { this.stdoutPid = stdoutPid; }
}
