package com.streamsql.fixture;

import com.streamsql.dto.*;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class TestBuilders {

    public static QualityRuleDTOBuilder qualityRuleDTO() {
        return new QualityRuleDTOBuilder();
    }

    public static VectorIndexDTOBuilder vectorIndexDTO() {
        return new VectorIndexDTOBuilder();
    }

    public static VectorSearchDTOBuilder vectorSearchDTO() {
        return new VectorSearchDTOBuilder();
    }

    public static StreamQueryDTOBuilder streamQueryDTO() {
        return new StreamQueryDTOBuilder();
    }

    public static DatasourceDTOBuilder datasourceDTO() {
        return new DatasourceDTOBuilder();
    }

    public static CdcTaskDTOBuilder cdcTaskDTO() {
        return new CdcTaskDTOBuilder();
    }

    public static LifecyclePolicyDTOBuilder lifecyclePolicyDTO() {
        return new LifecyclePolicyDTOBuilder();
    }

    public static TimeseriesDataDTOBuilder timeseriesDataDTO() {
        return new TimeseriesDataDTOBuilder();
    }

    public static LineageParseDTOBuilder lineageParseDTO() {
        return new LineageParseDTOBuilder();
    }

    public static class QualityRuleDTOBuilder {
        private String ruleName = "测试规则-" + UUID.randomUUID();
        private String ruleType = "regex";
        private String datasourceId = "ds_001";
        private String tableName = "users";
        private String columnName = "email";
        private String checkExpression = "^[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}$";
        private String severity = "error";
        private Boolean enabled = true;
        private String cronExpression = "0 0 * * * *";

        public QualityRuleDTOBuilder ruleName(String ruleName) {
            this.ruleName = ruleName;
            return this;
        }

        public QualityRuleDTOBuilder ruleType(String ruleType) {
            this.ruleType = ruleType;
            return this;
        }

        public QualityRuleDTOBuilder datasourceId(String datasourceId) {
            this.datasourceId = datasourceId;
            return this;
        }

        public QualityRuleDTOBuilder tableName(String tableName) {
            this.tableName = tableName;
            return this;
        }

        public QualityRuleDTOBuilder columnName(String columnName) {
            this.columnName = columnName;
            return this;
        }

        public QualityRuleDTOBuilder checkExpression(String checkExpression) {
            this.checkExpression = checkExpression;
            return this;
        }

        public QualityRuleDTOBuilder severity(String severity) {
            this.severity = severity;
            return this;
        }

        public QualityRuleDTOBuilder enabled(Boolean enabled) {
            this.enabled = enabled;
            return this;
        }

        public QualityRuleDTOBuilder cronExpression(String cronExpression) {
            this.cronExpression = cronExpression;
            return this;
        }

        public QualityRuleDTOBuilder withEmptyValues() {
            this.ruleName = "";
            this.ruleType = "";
            this.datasourceId = "";
            this.tableName = "";
            this.columnName = "";
            this.checkExpression = "";
            this.severity = "";
            this.enabled = false;
            this.cronExpression = "";
            return this;
        }

        public QualityRuleDTOBuilder withNullValues() {
            this.ruleName = null;
            this.ruleType = null;
            this.datasourceId = null;
            this.tableName = null;
            this.columnName = null;
            this.checkExpression = null;
            this.severity = null;
            this.enabled = null;
            this.cronExpression = null;
            return this;
        }

        public QualityRuleDTOBuilder withLongStrings() {
            this.ruleName = "A".repeat(1000);
            this.checkExpression = "X".repeat(2000);
            this.cronExpression = "C".repeat(500);
            return this;
        }

        public QualityRuleDTO build() {
            QualityRuleDTO dto = new QualityRuleDTO();
            dto.setRuleName(ruleName);
            dto.setRuleType(ruleType);
            dto.setDatasourceId(datasourceId);
            dto.setTableName(tableName);
            dto.setColumnName(columnName);
            dto.setCheckExpression(checkExpression);
            dto.setSeverity(severity);
            dto.setEnabled(enabled);
            dto.setCronExpression(cronExpression);
            return dto;
        }
    }

    public static class VectorIndexDTOBuilder {
        private String indexName = "测试索引-" + UUID.randomUUID();
        private String datasourceId = "ds_001";
        private String tableName = "users";
        private String columnName = "embedding";
        private Integer vectorDimension = 1536;
        private String indexType = "hnsw";
        private Map<String, Object> indexParams = new HashMap<>();

        public VectorIndexDTOBuilder() {
            indexParams.put("M", 16);
            indexParams.put("efConstruction", 100);
        }

        public VectorIndexDTOBuilder indexName(String indexName) {
            this.indexName = indexName;
            return this;
        }

        public VectorIndexDTOBuilder datasourceId(String datasourceId) {
            this.datasourceId = datasourceId;
            return this;
        }

        public VectorIndexDTOBuilder tableName(String tableName) {
            this.tableName = tableName;
            return this;
        }

        public VectorIndexDTOBuilder columnName(String columnName) {
            this.columnName = columnName;
            return this;
        }

        public VectorIndexDTOBuilder vectorDimension(Integer vectorDimension) {
            this.vectorDimension = vectorDimension;
            return this;
        }

        public VectorIndexDTOBuilder indexType(String indexType) {
            this.indexType = indexType;
            return this;
        }

        public VectorIndexDTOBuilder indexParams(Map<String, Object> indexParams) {
            this.indexParams = indexParams;
            return this;
        }

        public VectorIndexDTOBuilder withEmptyValues() {
            this.indexName = "";
            this.datasourceId = "";
            this.tableName = "";
            this.columnName = "";
            this.vectorDimension = 0;
            this.indexType = "";
            this.indexParams = new HashMap<>();
            return this;
        }

        public VectorIndexDTOBuilder withNullValues() {
            this.indexName = null;
            this.datasourceId = null;
            this.tableName = null;
            this.columnName = null;
            this.vectorDimension = null;
            this.indexType = null;
            this.indexParams = null;
            return this;
        }

        public VectorIndexDTOBuilder withLongStrings() {
            this.indexName = "X".repeat(1000);
            this.columnName = "Y".repeat(500);
            return this;
        }

        public VectorIndexDTO build() {
            VectorIndexDTO dto = new VectorIndexDTO();
            dto.setIndexName(indexName);
            dto.setDatasourceId(datasourceId);
            dto.setTableName(tableName);
            dto.setColumnName(columnName);
            dto.setVectorDimension(vectorDimension);
            dto.setIndexType(indexType);
            dto.setIndexParams(indexParams);
            return dto;
        }
    }

    public static class VectorSearchDTOBuilder {
        private java.util.List<Float> vector = TestFixtures.createRandomVector(1536);
        private Integer topK = 10;

        public VectorSearchDTOBuilder vector(java.util.List<Float> vector) {
            this.vector = vector;
            return this;
        }

        public VectorSearchDTOBuilder topK(Integer topK) {
            this.topK = topK;
            return this;
        }

        public VectorSearchDTOBuilder withEmptyVector() {
            this.vector = new java.util.ArrayList<>();
            return this;
        }

        public VectorSearchDTOBuilder withNullVector() {
            this.vector = null;
            return this;
        }

        public VectorSearchDTOBuilder withZeroTopK() {
            this.topK = 0;
            return this;
        }

        public VectorSearchDTOBuilder withNegativeTopK() {
            this.topK = -1;
            return this;
        }

        public VectorSearchDTO build() {
            VectorSearchDTO dto = new VectorSearchDTO();
            dto.setVector(vector);
            dto.setTopK(topK);
            return dto;
        }
    }

    public static class StreamQueryDTOBuilder {
        private String sql = "SELECT * FROM users WHERE age > 18";
        private Integer timeout = 30000;
        private Integer maxRecords = 1000;

        public StreamQueryDTOBuilder sql(String sql) {
            this.sql = sql;
            return this;
        }

        public StreamQueryDTOBuilder timeout(Integer timeout) {
            this.timeout = timeout;
            return this;
        }

        public StreamQueryDTOBuilder maxRecords(Integer maxRecords) {
            this.maxRecords = maxRecords;
            return this;
        }

        public StreamQueryDTOBuilder withEmptySql() {
            this.sql = "";
            return this;
        }

        public StreamQueryDTOBuilder withNullSql() {
            this.sql = null;
            return this;
        }

        public StreamQueryDTOBuilder withInvalidSql() {
            this.sql = "SELECT * FROM WHERE age > 18";
            return this;
        }

        public StreamQueryDTOBuilder withLongSql() {
            this.sql = "SELECT " + "A".repeat(5000) + " FROM users";
            return this;
        }

        public StreamQueryDTO build() {
            StreamQueryDTO dto = new StreamQueryDTO();
            dto.setSql(sql);
            dto.setTimeout(timeout);
            dto.setMaxRecords(maxRecords);
            return dto;
        }
    }

    public static class DatasourceDTOBuilder {
        private String datasourceName = "测试数据源-" + UUID.randomUUID();
        private String datasourceType = "mysql";
        private Map<String, Object> connectionConfig = new HashMap<>();
        private String description = "测试用数据源";

        public DatasourceDTOBuilder() {
            connectionConfig.put("host", "localhost");
            connectionConfig.put("port", 3306);
            connectionConfig.put("database", "test_db");
            connectionConfig.put("username", "root");
            connectionConfig.put("password", "password");
        }

        public DatasourceDTOBuilder datasourceName(String datasourceName) {
            this.datasourceName = datasourceName;
            return this;
        }

        public DatasourceDTOBuilder datasourceType(String datasourceType) {
            this.datasourceType = datasourceType;
            return this;
        }

        public DatasourceDTOBuilder connectionConfig(Map<String, Object> connectionConfig) {
            this.connectionConfig = connectionConfig;
            return this;
        }

        public DatasourceDTOBuilder description(String description) {
            this.description = description;
            return this;
        }

        public DatasourceDTOBuilder withEmptyValues() {
            this.datasourceName = "";
            this.datasourceType = "";
            this.connectionConfig = new HashMap<>();
            this.description = "";
            return this;
        }

        public DatasourceDTOBuilder withInvalidType() {
            this.datasourceType = "invalid_type";
            return this;
        }

        public DatasourceDTO build() {
            DatasourceDTO dto = new DatasourceDTO();
            dto.setDatasourceName(datasourceName);
            dto.setDatasourceType(datasourceType);
            dto.setConnectionConfig(connectionConfig);
            dto.setDescription(description);
            return dto;
        }
    }

    public static class CdcTaskDTOBuilder {
        private String taskName = "测试CDC任务-" + UUID.randomUUID();
        private String datasourceId = "ds_001";
        private String tableName = "users";
        private String eventType = "ALL";
        private String outputType = "kafka";
        private Map<String, Object> outputConfig = new HashMap<>();
        private Boolean enabled = true;

        public CdcTaskDTOBuilder() {
            outputConfig.put("topic", "cdc_events");
            outputConfig.put("bootstrapServers", "localhost:9092");
        }

        public CdcTaskDTOBuilder taskName(String taskName) {
            this.taskName = taskName;
            return this;
        }

        public CdcTaskDTOBuilder datasourceId(String datasourceId) {
            this.datasourceId = datasourceId;
            return this;
        }

        public CdcTaskDTOBuilder tableName(String tableName) {
            this.tableName = tableName;
            return this;
        }

        public CdcTaskDTOBuilder eventType(String eventType) {
            this.eventType = eventType;
            return this;
        }

        public CdcTaskDTOBuilder outputType(String outputType) {
            this.outputType = outputType;
            return this;
        }

        public CdcTaskDTOBuilder withEmptyValues() {
            this.taskName = "";
            this.datasourceId = "";
            this.tableName = "";
            this.eventType = "";
            this.outputType = "";
            this.outputConfig = new HashMap<>();
            this.enabled = false;
            return this;
        }

        public CdcTaskDTO build() {
            CdcTaskDTO dto = new CdcTaskDTO();
            dto.setTaskName(taskName);
            dto.setDatasourceId(datasourceId);
            dto.setTableName(tableName);
            dto.setEventType(eventType);
            dto.setOutputType(outputType);
            dto.setOutputConfig(outputConfig);
            dto.setEnabled(enabled);
            return dto;
        }
    }

    public static class LifecyclePolicyDTOBuilder {
        private String policyName = "测试策略-" + UUID.randomUUID();
        private String datasourceId = "ds_001";
        private String tableName = "users";
        private Integer hotStorageDays = 30;
        private Integer coldStorageDays = 90;
        private Integer archiveStorageDays = 365;
        private Boolean enabled = true;

        public LifecyclePolicyDTOBuilder policyName(String policyName) {
            this.policyName = policyName;
            return this;
        }

        public LifecyclePolicyDTOBuilder datasourceId(String datasourceId) {
            this.datasourceId = datasourceId;
            return this;
        }

        public LifecyclePolicyDTOBuilder tableName(String tableName) {
            this.tableName = tableName;
            return this;
        }

        public LifecyclePolicyDTOBuilder hotStorageDays(Integer hotStorageDays) {
            this.hotStorageDays = hotStorageDays;
            return this;
        }

        public LifecyclePolicyDTOBuilder coldStorageDays(Integer coldStorageDays) {
            this.coldStorageDays = coldStorageDays;
            return this;
        }

        public LifecyclePolicyDTOBuilder archiveStorageDays(Integer archiveStorageDays) {
            this.archiveStorageDays = archiveStorageDays;
            return this;
        }

        public LifecyclePolicyDTOBuilder enabled(Boolean enabled) {
            this.enabled = enabled;
            return this;
        }

        public LifecyclePolicyDTOBuilder withEmptyValues() {
            this.policyName = "";
            this.datasourceId = "";
            this.tableName = "";
            this.hotStorageDays = null;
            this.coldStorageDays = null;
            this.archiveStorageDays = null;
            this.enabled = false;
            return this;
        }

        public LifecyclePolicyDTOBuilder withZeroDays() {
            this.hotStorageDays = 0;
            this.coldStorageDays = 0;
            this.archiveStorageDays = 0;
            return this;
        }

        public LifecyclePolicyDTO build() {
            LifecyclePolicyDTO dto = new LifecyclePolicyDTO();
            dto.setPolicyName(policyName);
            dto.setDatasourceId(datasourceId);
            dto.setTableName(tableName);
            dto.setHotStorageDays(hotStorageDays);
            dto.setColdStorageDays(coldStorageDays);
            dto.setArchiveStorageDays(archiveStorageDays);
            dto.setEnabled(enabled);
            return dto;
        }
    }

    public static class TimeseriesDataDTOBuilder {
        private String metricName = "cpu_usage";
        private java.time.LocalDateTime timestamp = java.time.LocalDateTime.now();
        private Double metricValue = 42.5;
        private Map<String, Object> tags = new HashMap<>();

        public TimeseriesDataDTOBuilder() {
            tags.put("host", "server-01");
            tags.put("region", "cn-east");
        }

        public TimeseriesDataDTOBuilder metricName(String metricName) {
            this.metricName = metricName;
            return this;
        }

        public TimeseriesDataDTOBuilder timestamp(java.time.LocalDateTime timestamp) {
            this.timestamp = timestamp;
            return this;
        }

        public TimeseriesDataDTOBuilder metricValue(Double metricValue) {
            this.metricValue = metricValue;
            return this;
        }

        public TimeseriesDataDTOBuilder tags(Map<String, Object> tags) {
            this.tags = tags;
            return this;
        }

        public TimeseriesDataDTOBuilder withEmptyValues() {
            this.metricName = "";
            this.timestamp = null;
            this.metricValue = null;
            this.tags = new HashMap<>();
            return this;
        }

        public TimeseriesDataDTOBuilder withZeroValue() {
            this.metricValue = 0.0;
            return this;
        }

        public TimeseriesDataDTOBuilder withNegativeValue() {
            this.metricValue = -100.0;
            return this;
        }

        public TimeseriesDataDTO build() {
            TimeseriesDataDTO dto = new TimeseriesDataDTO();
            dto.setMetricName(metricName);
            dto.setTimestamp(timestamp);
            dto.setMetricValue(metricValue);
            dto.setTags(tags);
            return dto;
        }
    }

    public static class LineageParseDTOBuilder {
        private String sql = "INSERT INTO target_table SELECT a.id, b.name FROM source_a a JOIN source_b b ON a.id = b.id";

        public LineageParseDTOBuilder sql(String sql) {
            this.sql = sql;
            return this;
        }

        public LineageParseDTOBuilder withEmptySql() {
            this.sql = "";
            return this;
        }

        public LineageParseDTOBuilder withNullSql() {
            this.sql = null;
            return this;
        }

        public LineageParseDTOBuilder withInvalidSql() {
            this.sql = "INSERT INTO SELECT FROM WHERE";
            return this;
        }

        public LineageParseDTOBuilder withComplexSql() {
            this.sql = "WITH cte AS (SELECT id, name FROM users WHERE status = 'active') SELECT c.id, o.total FROM cte c JOIN orders o ON c.id = o.user_id";
            return this;
        }

        public LineageParseDTO build() {
            LineageParseDTO dto = new LineageParseDTO();
            dto.setSql(sql);
            return dto;
        }
    }
}
