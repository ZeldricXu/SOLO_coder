package com.example.mailservice.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;

@Data
@Configuration
@ConfigurationProperties(prefix = "app")
public class AppConfig {
    private MailConfig mail = new MailConfig();
    private SearchConfig search = new SearchConfig();
    private RedisQueueConfig redisQueue = new RedisQueueConfig();
    private RulePriorityConfig rulePriority = new RulePriorityConfig();

    @Data
    public static class MailConfig {
        private String attachmentPath;
        private long maxAttachmentSize;
        private int retryCount;
        private long retryInterval;
        private ImapConfig imap = new ImapConfig();

        @Data
        public static class ImapConfig {
            private String host;
            private int port;
            private String protocol;
            private String username;
            private String password;
        }
    }

    @Data
    public static class SearchConfig {
        private int pageSize = 20;
        private boolean asyncIndexing = true;
    }

    @Data
    public static class RedisQueueConfig {
        private String sendQueueKey = "mail:send:queue";
        private String indexQueueKey = "mail:index:queue";
        private String sendProcessingKey = "mail:send:processing";
        private String indexProcessingKey = "mail:index:processing";
        private String sendDeadLetterKey = "mail:send:deadletter";
        private String indexDeadLetterKey = "mail:index:deadletter";
        private int sendWorkerCount = 5;
        private int indexWorkerCount = 3;
        private long pollTimeout = 5000;
        private int maxRetries = 3;
        private long retryDelay = 60000;
    }

    @Data
    public static class RulePriorityConfig {
        private boolean dynamicSortingEnabled = true;
        private int decayIntervalMinutes = 60;
        private double decayFactor = 0.9;
        private int minPriority = 0;
        private int maxPriority = 1000;
        private int adjustmentThreshold = 10;
        private List<RuleConfig> rules = new ArrayList<>();

        @Data
        public static class RuleConfig {
            private String ruleName;
            private String rulePattern;
            private String targetCategory;
            private int basePriority = 50;
            private boolean enabled = true;
        }
    }
}
