package com.loganalytics.common.config;

public final class KafkaTopics {
    public static final String RAW_LOGS = "raw-logs";
    public static final String PARSED_LOGS = "parsed-logs";
    public static final String ENRICHED_LOGS = "enriched-logs";
    public static final String ERROR_LOGS = "error-logs";
    public static final String ANOMALIES = "anomalies";
    public static final String METRICS = "metrics";
    public static final String ALERTS = "alerts";
    public static final String ARCHIVE_LOGS = "archive-logs";

    public static final int DEFAULT_PARTITIONS = 12;
    public static final short DEFAULT_REPLICATION = 2;

    private KafkaTopics() {}
}
