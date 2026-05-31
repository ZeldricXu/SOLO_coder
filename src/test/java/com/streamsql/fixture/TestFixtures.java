package com.streamsql.fixture;

import com.streamsql.dto.*;
import com.streamsql.entity.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

public class TestFixtures {

    public static final String SAMPLE_DATASOURCE_ID = "ds_001";
    public static final String SAMPLE_TABLE_NAME = "users";
    public static final String SAMPLE_COLUMN_NAME = "email";
    public static final String SAMPLE_RULE_ID = "rule_001";
    public static final String SAMPLE_INDEX_ID = "idx_001";
    public static final String SAMPLE_TASK_ID = "task_001";
    public static final String SAMPLE_GRAPH_ID = "graph_001";
    public static final String SAMPLE_POLICY_ID = "policy_001";
    public static final String SAMPLE_METRIC_NAME = "cpu_usage";

    public static QualityRuleDTO createQualityRuleDTO() {
        QualityRuleDTO dto = new QualityRuleDTO();
        dto.setRuleName("测试质量规则-" + UUID.randomUUID());
        dto.setRuleType("regex");
        dto.setDatasourceId(SAMPLE_DATASOURCE_ID);
        dto.setTableName(SAMPLE_TABLE_NAME);
        dto.setColumnName(SAMPLE_COLUMN_NAME);
        dto.setCheckExpression("^[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}$");
        dto.setSeverity("error");
        dto.setEnabled(true);
        dto.setCronExpression("0 0 * * * *");
        return dto;
    }

    public static QualityRuleDTO createQualityRuleDTOWithEmptyValues() {
        QualityRuleDTO dto = new QualityRuleDTO();
        dto.setRuleName("");
        dto.setRuleType("");
        dto.setDatasourceId("");
        dto.setTableName("");
        dto.setColumnName("");
        dto.setCheckExpression("");
        dto.setSeverity("");
        dto.setEnabled(false);
        dto.setCronExpression("");
        return dto;
    }

    public static QualityRuleDTO createQualityRuleDTOWithNullValues() {
        return new QualityRuleDTO();
    }

    public static QualityRuleDTO createQualityRuleDTOWithLongStrings() {
        QualityRuleDTO dto = createQualityRuleDTO();
        dto.setRuleName("A".repeat(1000));
        dto.setCheckExpression("X".repeat(2000));
        dto.setCronExpression("C".repeat(500));
        return dto;
    }

    public static QualityRuleDTO createQualityRuleDTOWithZeroValues() {
        QualityRuleDTO dto = createQualityRuleDTO();
        dto.setEnabled(false);
        dto.setCronExpression(null);
        return dto;
    }

    public static QualityRule createQualityRuleEntity() {
        QualityRule rule = new QualityRule();
        rule.setRuleId(SAMPLE_RULE_ID);
        rule.setRuleName("测试质量规则");
        rule.setRuleType("regex");
        rule.setDatasourceId(SAMPLE_DATASOURCE_ID);
        rule.setTableName(SAMPLE_TABLE_NAME);
        rule.setColumnName(SAMPLE_COLUMN_NAME);
        rule.setCheckExpression("^[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}$");
        rule.setSeverity("error");
        rule.setEnabled(true);
        rule.setCronExpression("0 0 * * * *");
        rule.setCreatedAt(LocalDateTime.now());
        rule.setUpdatedAt(LocalDateTime.now());
        rule.setDeleted(false);
        return rule;
    }

    public static QualityCheckResult createQualityCheckResultEntity() {
        QualityCheckResult result = new QualityCheckResult();
        result.setResultId("result_" + UUID.randomUUID());
        result.setRuleId(SAMPLE_RULE_ID);
        result.setCheckTime(LocalDateTime.now());
        result.setStatus("success");
        result.setTotalCount(1000L);
        result.setErrorCount(0L);
        result.setCreatedAt(LocalDateTime.now());
        result.setDeleted(false);
        return result;
    }

    public static AnomalyDataRecord createAnomalyDataRecordEntity() {
        AnomalyDataRecord record = new AnomalyDataRecord();
        record.setRecordId("anomaly_" + UUID.randomUUID());
        record.setRuleId(SAMPLE_RULE_ID);
        record.setDatasourceId(SAMPLE_DATASOURCE_ID);
        record.setTableName(SAMPLE_TABLE_NAME);
        record.setPrimaryKeyValue("sample_pk_123");
        record.setAnomalyType("regex");
        record.setAnomalyDetail("{\"violationType\":\"regex\",\"description\":\"Invalid email format\"}");
        record.setMarked(true);
        record.setCreatedAt(LocalDateTime.now());
        record.setDeleted(false);
        return record;
    }

    public static VectorIndexDTO createVectorIndexDTO() {
        VectorIndexDTO dto = new VectorIndexDTO();
        dto.setIndexName("测试向量索引-" + UUID.randomUUID());
        dto.setDatasourceId(SAMPLE_DATASOURCE_ID);
        dto.setTableName(SAMPLE_TABLE_NAME);
        dto.setColumnName("embedding");
        dto.setVectorDimension(1536);
        dto.setIndexType("hnsw");
        Map<String, Object> params = new HashMap<>();
        params.put("M", 16);
        params.put("efConstruction", 100);
        dto.setIndexParams(params);
        return dto;
    }

    public static VectorIndexDTO createVectorIndexDTOWithEmptyValues() {
        VectorIndexDTO dto = new VectorIndexDTO();
        dto.setIndexName("");
        dto.setDatasourceId("");
        dto.setTableName("");
        dto.setColumnName("");
        dto.setVectorDimension(0);
        dto.setIndexType("");
        dto.setIndexParams(new HashMap<>());
        return dto;
    }

    public static VectorIndexDTO createVectorIndexDTOWithNullValues() {
        return new VectorIndexDTO();
    }

    public static VectorIndexDTO createVectorIndexDTOWithLongStrings() {
        VectorIndexDTO dto = createVectorIndexDTO();
        dto.setIndexName("X".repeat(1000));
        dto.setColumnName("Y".repeat(500));
        return dto;
    }

    public static VectorIndexDTO createVectorIndexDTOWithZeroDimension() {
        VectorIndexDTO dto = createVectorIndexDTO();
        dto.setVectorDimension(0);
        return dto;
    }

    public static VectorIndexDTO createVectorIndexDTOWithNegativeDimension() {
        VectorIndexDTO dto = createVectorIndexDTO();
        dto.setVectorDimension(-1);
        return dto;
    }

    public static VectorSearchDTO createVectorSearchDTO() {
        VectorSearchDTO dto = new VectorSearchDTO();
        List<Float> vector = new ArrayList<>();
        for (int i = 0; i < 1536; i++) {
            vector.add((float) Math.random());
        }
        dto.setVector(vector);
        dto.setTopK(10);
        return dto;
    }

    public static VectorSearchDTO createVectorSearchDTOWithEmptyVector() {
        VectorSearchDTO dto = new VectorSearchDTO();
        dto.setVector(new ArrayList<>());
        dto.setTopK(10);
        return dto;
    }

    public static VectorSearchDTO createVectorSearchDTOWithNullVector() {
        VectorSearchDTO dto = new VectorSearchDTO();
        dto.setVector(null);
        dto.setTopK(10);
        return dto;
    }

    public static VectorSearchDTO createVectorSearchDTOWithZeroTopK() {
        VectorSearchDTO dto = createVectorSearchDTO();
        dto.setTopK(0);
        return dto;
    }

    public static VectorSearchDTO createVectorSearchDTOWithNegativeTopK() {
        VectorSearchDTO dto = createVectorSearchDTO();
        dto.setTopK(-1);
        return dto;
    }

    public static VectorSearchDTO createVectorSearchDTOWithVeryLargeTopK() {
        VectorSearchDTO dto = createVectorSearchDTO();
        dto.setTopK(100000);
        return dto;
    }

    public static VectorIndex createVectorIndexEntity() {
        VectorIndex index = new VectorIndex();
        index.setIndexId(SAMPLE_INDEX_ID);
        index.setIndexName("测试向量索引");
        index.setDatasourceId(SAMPLE_DATASOURCE_ID);
        index.setTableName(SAMPLE_TABLE_NAME);
        index.setColumnName("embedding");
        index.setVectorDimension(1536);
        index.setIndexType("hnsw");
        index.setIndexParams("{\"M\":16,\"efConstruction\":100}");
        index.setStatus("ready");
        index.setIndexPath("./data/vector-index/test.idx");
        index.setCreatedAt(LocalDateTime.now());
        index.setUpdatedAt(LocalDateTime.now());
        index.setDeleted(false);
        return index;
    }

    public static VectorEmbedding createVectorEmbeddingEntity() {
        VectorEmbedding embedding = new VectorEmbedding();
        embedding.setEmbeddingId("emb_" + UUID.randomUUID());
        embedding.setIndexId(SAMPLE_INDEX_ID);
        embedding.setDataKey("data_001");
        embedding.setVector(new byte[1536 * 4]);
        embedding.setMetadata("{\"source\":\"test\"}");
        embedding.setCreatedAt(LocalDateTime.now());
        embedding.setDeleted(false);
        return embedding;
    }

    public static StreamQueryDTO createStreamQueryDTO() {
        StreamQueryDTO dto = new StreamQueryDTO();
        dto.setSql("SELECT * FROM users WHERE age > 18");
        dto.setTimeout(30000);
        dto.setMaxRecords(1000);
        return dto;
    }

    public static StreamQueryDTO createStreamQueryDTOWithEmptySql() {
        StreamQueryDTO dto = new StreamQueryDTO();
        dto.setSql("");
        dto.setTimeout(30000);
        dto.setMaxRecords(1000);
        return dto;
    }

    public static StreamQueryDTO createStreamQueryDTOWithNullSql() {
        StreamQueryDTO dto = new StreamQueryDTO();
        dto.setSql(null);
        dto.setTimeout(30000);
        dto.setMaxRecords(1000);
        return dto;
    }

    public static StreamQueryDTO createStreamQueryDTOWithInvalidSql() {
        StreamQueryDTO dto = new StreamQueryDTO();
        dto.setSql("SELECT * FROM WHERE age > 18");
        dto.setTimeout(30000);
        dto.setMaxRecords(1000);
        return dto;
    }

    public static StreamQueryDTO createStreamQueryDTOWithLongSql() {
        StreamQueryDTO dto = new StreamQueryDTO();
        dto.setSql("SELECT " + "A".repeat(5000) + " FROM users");
        dto.setTimeout(30000);
        dto.setMaxRecords(1000);
        return dto;
    }

    public static StreamQueryDTO createStreamQueryDTOWithZeroTimeout() {
        StreamQueryDTO dto = createStreamQueryDTO();
        dto.setTimeout(0);
        return dto;
    }

    public static StreamQueryDTO createStreamQueryDTOWithNegativeTimeout() {
        StreamQueryDTO dto = createStreamQueryDTO();
        dto.setTimeout(-1);
        return dto;
    }

    public static StreamQueryPlan createStreamQueryPlanEntity() {
        StreamQueryPlan plan = new StreamQueryPlan();
        plan.setPlanId("plan_" + UUID.randomUUID());
        plan.setSql("SELECT * FROM users WHERE age > 18");
        plan.setLogicalPlan("{\"type\":\"SELECT\",\"table\":\"users\"}");
        plan.setPhysicalPlan("{\"type\":\"SCAN\",\"table\":\"users\"}");
        plan.setExecutionTime(50L);
        plan.setCreatedAt(LocalDateTime.now());
        plan.setDeleted(false);
        return plan;
    }

    public static DatasourceDTO createDatasourceDTO() {
        DatasourceDTO dto = new DatasourceDTO();
        dto.setDatasourceName("测试数据源-" + UUID.randomUUID());
        dto.setDatasourceType("mysql");
        Map<String, Object> config = new HashMap<>();
        config.put("host", "localhost");
        config.put("port", 3306);
        config.put("database", "test_db");
        config.put("username", "root");
        config.put("password", "password");
        dto.setConnectionConfig(config);
        dto.setDescription("测试用数据源");
        return dto;
    }

    public static DatasourceDTO createDatasourceDTOWithEmptyValues() {
        DatasourceDTO dto = new DatasourceDTO();
        dto.setDatasourceName("");
        dto.setDatasourceType("");
        dto.setConnectionConfig(new HashMap<>());
        dto.setDescription("");
        return dto;
    }

    public static DatasourceDTO createDatasourceDTOWithNullValues() {
        return new DatasourceDTO();
    }

    public static DatasourceDTO createDatasourceDTOWithInvalidType() {
        DatasourceDTO dto = createDatasourceDTO();
        dto.setDatasourceType("invalid_type");
        return dto;
    }

    public static DatasourceDTO createDatasourceDTOWithLongStrings() {
        DatasourceDTO dto = createDatasourceDTO();
        dto.setDatasourceName("N".repeat(1000));
        dto.setDescription("D".repeat(2000));
        return dto;
    }

    public static DatasourceInfo createDatasourceInfoEntity() {
        DatasourceInfo info = new DatasourceInfo();
        info.setDatasourceId(SAMPLE_DATASOURCE_ID);
        info.setDatasourceName("测试数据源");
        info.setDatasourceType("mysql");
        info.setConnectionConfig("{\"host\":\"localhost\",\"port\":3306}");
        info.setDescription("测试用数据源");
        info.setStatus("active");
        info.setCreatedAt(LocalDateTime.now());
        info.setUpdatedAt(LocalDateTime.now());
        info.setDeleted(false);
        return info;
    }

    public static MetadataSchema createMetadataSchemaEntity() {
        MetadataSchema schema = new MetadataSchema();
        schema.setSchemaId("schema_" + UUID.randomUUID());
        schema.setDatasourceId(SAMPLE_DATASOURCE_ID);
        schema.setTableName(SAMPLE_TABLE_NAME);
        schema.setColumnName("id");
        schema.setDataType("BIGINT");
        schema.setIsNullable(false);
        schema.setIsPrimaryKey(true);
        schema.setColumnComment("主键ID");
        schema.setCreatedAt(LocalDateTime.now());
        schema.setDeleted(false);
        return schema;
    }

    public static MetadataStatistics createMetadataStatisticsEntity() {
        MetadataStatistics stats = new MetadataStatistics();
        stats.setStatsId("stats_" + UUID.randomUUID());
        stats.setDatasourceId(SAMPLE_DATASOURCE_ID);
        stats.setTableName(SAMPLE_TABLE_NAME);
        stats.setColumnName("id");
        stats.setRowCount(10000L);
        stats.setDistinctCount(9500L);
        stats.setNullCount(0L);
        stats.setMinValue("1");
        stats.setMaxValue("10000");
        stats.setCreatedAt(LocalDateTime.now());
        stats.setDeleted(false);
        return stats;
    }

    public static SampleData createSampleDataEntity() {
        SampleData sample = new SampleData();
        sample.setSampleId("sample_" + UUID.randomUUID());
        sample.setDatasourceId(SAMPLE_DATASOURCE_ID);
        sample.setTableName(SAMPLE_TABLE_NAME);
        sample.setSampleData("{\"id\":1,\"name\":\"test\"}");
        sample.setCreatedAt(LocalDateTime.now());
        sample.setDeleted(false);
        return sample;
    }

    public static CdcTaskDTO createCdcTaskDTO() {
        CdcTaskDTO dto = new CdcTaskDTO();
        dto.setTaskName("测试CDC任务-" + UUID.randomUUID());
        dto.setDatasourceId(SAMPLE_DATASOURCE_ID);
        dto.setTableName(SAMPLE_TABLE_NAME);
        dto.setEventType("ALL");
        dto.setOutputType("kafka");
        Map<String, Object> outputConfig = new HashMap<>();
        outputConfig.put("topic", "cdc_events");
        outputConfig.put("bootstrapServers", "localhost:9092");
        dto.setOutputConfig(outputConfig);
        dto.setEnabled(true);
        return dto;
    }

    public static CdcTaskDTO createCdcTaskDTOWithEmptyValues() {
        CdcTaskDTO dto = new CdcTaskDTO();
        dto.setTaskName("");
        dto.setDatasourceId("");
        dto.setTableName("");
        dto.setEventType("");
        dto.setOutputType("");
        dto.setOutputConfig(new HashMap<>());
        dto.setEnabled(false);
        return dto;
    }

    public static CdcTaskDTO createCdcTaskDTOWithNullValues() {
        return new CdcTaskDTO();
    }

    public static CdcTaskDTO createCdcTaskDTOWithInvalidOutputType() {
        CdcTaskDTO dto = createCdcTaskDTO();
        dto.setOutputType("invalid_type");
        return dto;
    }

    public static CdcCaptureTask createCdcCaptureTaskEntity() {
        CdcCaptureTask task = new CdcCaptureTask();
        task.setTaskId(SAMPLE_TASK_ID);
        task.setTaskName("测试CDC任务");
        task.setDatasourceId(SAMPLE_DATASOURCE_ID);
        task.setTableName(SAMPLE_TABLE_NAME);
        task.setEventType("ALL");
        task.setOutputType("kafka");
        task.setOutputConfig("{\"topic\":\"cdc_events\"}");
        task.setStatus("stopped");
        task.setEnabled(true);
        task.setCreatedAt(LocalDateTime.now());
        task.setUpdatedAt(LocalDateTime.now());
        task.setDeleted(false);
        return task;
    }

    public static CdcEventRecord createCdcEventRecordEntity() {
        CdcEventRecord record = new CdcEventRecord();
        record.setEventId("event_" + UUID.randomUUID());
        record.setTaskId(SAMPLE_TASK_ID);
        record.setEventType("INSERT");
        record.setTableName(SAMPLE_TABLE_NAME);
        record.setEventData("{\"id\":1,\"name\":\"test\"}");
        record.setEventTime(LocalDateTime.now());
        record.setCreatedAt(LocalDateTime.now());
        record.setDeleted(false);
        return record;
    }

    public static LineageParseDTO createLineageParseDTO() {
        LineageParseDTO dto = new LineageParseDTO();
        dto.setSql("INSERT INTO target_table SELECT a.id, b.name FROM source_a a JOIN source_b b ON a.id = b.id");
        return dto;
    }

    public static LineageParseDTO createLineageParseDTOWithEmptySql() {
        LineageParseDTO dto = new LineageParseDTO();
        dto.setSql("");
        return dto;
    }

    public static LineageParseDTO createLineageParseDTOWithNullSql() {
        LineageParseDTO dto = new LineageParseDTO();
        dto.setSql(null);
        return dto;
    }

    public static LineageParseDTO createLineageParseDTOWithInvalidSql() {
        LineageParseDTO dto = new LineageParseDTO();
        dto.setSql("INSERT INTO SELECT FROM WHERE");
        return dto;
    }

    public static LineageParseDTO createLineageParseDTOWithComplexSql() {
        LineageParseDTO dto = new LineageParseDTO();
        dto.setSql("WITH cte AS (SELECT id, name FROM users WHERE status = 'active') SELECT c.id, o.total FROM cte c JOIN orders o ON c.id = o.user_id");
        return dto;
    }

    public static LineageParseDTO createLineageParseDTOWithLongSql() {
        LineageParseDTO dto = new LineageParseDTO();
        StringBuilder sql = new StringBuilder("SELECT ");
        for (int i = 0; i < 100; i++) {
            if (i > 0) sql.append(", ");
            sql.append("col").append(i);
        }
        sql.append(" FROM users WHERE id = 1");
        dto.setSql(sql.toString());
        return dto;
    }

    public static LineageGraph createLineageGraphEntity() {
        LineageGraph graph = new LineageGraph();
        graph.setGraphId(SAMPLE_GRAPH_ID);
        graph.setGraphName("测试血缘图谱");
        graph.setSourceSql("INSERT INTO target_table SELECT a.id, b.name FROM source_a a JOIN source_b b ON a.id = b.id");
        graph.setNodeCount(3);
        graph.setEdgeCount(2);
        graph.setCreatedAt(LocalDateTime.now());
        graph.setDeleted(false);
        return graph;
    }

    public static LineageNode createLineageNodeEntity() {
        LineageNode node = new LineageNode();
        node.setNodeId("node_" + UUID.randomUUID());
        node.setGraphId(SAMPLE_GRAPH_ID);
        node.setNodeType("table");
        node.setNodeName("source_a");
        node.setMetadata("{\"schema\":\"public\"}");
        node.setCreatedAt(LocalDateTime.now());
        node.setDeleted(false);
        return node;
    }

    public static LineageEdge createLineageEdgeEntity() {
        LineageEdge edge = new LineageEdge();
        edge.setEdgeId("edge_" + UUID.randomUUID());
        edge.setGraphId(SAMPLE_GRAPH_ID);
        edge.setSourceNodeId("node_source");
        edge.setTargetNodeId("node_target");
        edge.setEdgeType("follows");
        edge.setCreatedAt(LocalDateTime.now());
        edge.setDeleted(false);
        return edge;
    }

    public static TimeseriesDataDTO createTimeseriesDataDTO() {
        TimeseriesDataDTO dto = new TimeseriesDataDTO();
        dto.setMetricName(SAMPLE_METRIC_NAME);
        dto.setTimestamp(LocalDateTime.now());
        dto.setMetricValue(42.5);
        Map<String, Object> tags = new HashMap<>();
        tags.put("host", "server-01");
        tags.put("region", "cn-east");
        dto.setTags(tags);
        return dto;
    }

    public static TimeseriesDataDTO createTimeseriesDataDTOWithEmptyValues() {
        TimeseriesDataDTO dto = new TimeseriesDataDTO();
        dto.setMetricName("");
        dto.setTimestamp(null);
        dto.setMetricValue(null);
        dto.setTags(new HashMap<>());
        return dto;
    }

    public static TimeseriesDataDTO createTimeseriesDataDTOWithNullValues() {
        return new TimeseriesDataDTO();
    }

    public static TimeseriesDataDTO createTimeseriesDataDTOWithZeroValue() {
        TimeseriesDataDTO dto = createTimeseriesDataDTO();
        dto.setMetricValue(0.0);
        return dto;
    }

    public static TimeseriesDataDTO createTimeseriesDataDTOWithNegativeValue() {
        TimeseriesDataDTO dto = createTimeseriesDataDTO();
        dto.setMetricValue(-100.0);
        return dto;
    }

    public static TimeseriesDataDTO createTimeseriesDataDTOWithVeryLargeValue() {
        TimeseriesDataDTO dto = createTimeseriesDataDTO();
        dto.setMetricValue(Double.MAX_VALUE);
        return dto;
    }

    public static TimeseriesDataDTO createTimeseriesDataDTOWithLongMetricName() {
        TimeseriesDataDTO dto = createTimeseriesDataDTO();
        dto.setMetricName("M".repeat(1000));
        return dto;
    }

    public static TimeseriesData createTimeseriesDataEntity() {
        TimeseriesData data = new TimeseriesData();
        data.setDataId("ts_" + UUID.randomUUID());
        data.setMetricName(SAMPLE_METRIC_NAME);
        data.setTimestamp(LocalDateTime.now());
        data.setMetricValue(42.5);
        data.setTags("{\"host\":\"server-01\"}");
        data.setResolution("raw");
        data.setCompressed(false);
        data.setCreatedAt(LocalDateTime.now());
        data.setDeleted(false);
        return data;
    }

    public static LifecyclePolicyDTO createLifecyclePolicyDTO() {
        LifecyclePolicyDTO dto = new LifecyclePolicyDTO();
        dto.setPolicyName("测试生命周期策略-" + UUID.randomUUID());
        dto.setDatasourceId(SAMPLE_DATASOURCE_ID);
        dto.setTableName(SAMPLE_TABLE_NAME);
        dto.setHotStorageDays(30);
        dto.setColdStorageDays(90);
        dto.setArchiveStorageDays(365);
        dto.setEnabled(true);
        return dto;
    }

    public static LifecyclePolicyDTO createLifecyclePolicyDTOWithEmptyValues() {
        LifecyclePolicyDTO dto = new LifecyclePolicyDTO();
        dto.setPolicyName("");
        dto.setDatasourceId("");
        dto.setTableName("");
        dto.setHotStorageDays(null);
        dto.setColdStorageDays(null);
        dto.setArchiveStorageDays(null);
        dto.setEnabled(false);
        return dto;
    }

    public static LifecyclePolicyDTO createLifecyclePolicyDTOWithNullValues() {
        return new LifecyclePolicyDTO();
    }

    public static LifecyclePolicyDTO createLifecyclePolicyDTOWithZeroDays() {
        LifecyclePolicyDTO dto = createLifecyclePolicyDTO();
        dto.setHotStorageDays(0);
        dto.setColdStorageDays(0);
        dto.setArchiveStorageDays(0);
        return dto;
    }

    public static LifecyclePolicyDTO createLifecyclePolicyDTOWithNegativeDays() {
        LifecyclePolicyDTO dto = createLifecyclePolicyDTO();
        dto.setHotStorageDays(-1);
        dto.setColdStorageDays(-1);
        dto.setArchiveStorageDays(-1);
        return dto;
    }

    public static LifecyclePolicyDTO createLifecyclePolicyDTOWithVeryLargeDays() {
        LifecyclePolicyDTO dto = createLifecyclePolicyDTO();
        dto.setHotStorageDays(Integer.MAX_VALUE);
        dto.setColdStorageDays(Integer.MAX_VALUE);
        dto.setArchiveStorageDays(Integer.MAX_VALUE);
        return dto;
    }

    public static LifecyclePolicy createLifecyclePolicyEntity() {
        LifecyclePolicy policy = new LifecyclePolicy();
        policy.setPolicyId(SAMPLE_POLICY_ID);
        policy.setPolicyName("测试生命周期策略");
        policy.setDatasourceId(SAMPLE_DATASOURCE_ID);
        policy.setTableName(SAMPLE_TABLE_NAME);
        policy.setHotStorageDays(30);
        policy.setColdStorageDays(90);
        policy.setArchiveStorageDays(365);
        policy.setEnabled(true);
        policy.setCreatedAt(LocalDateTime.now());
        policy.setUpdatedAt(LocalDateTime.now());
        policy.setDeleted(false);
        return policy;
    }

    public static DataArchiveRecord createDataArchiveRecordEntity() {
        DataArchiveRecord record = new DataArchiveRecord();
        record.setArchiveId("archive_" + UUID.randomUUID());
        record.setPolicyId(SAMPLE_POLICY_ID);
        record.setDatasourceId(SAMPLE_DATASOURCE_ID);
        record.setTableName(SAMPLE_TABLE_NAME);
        record.setArchiveType("cold");
        record.setArchivePath("./data/archive/test.json.gz");
        record.setArchiveCount(1000L);
        record.setArchiveDate(LocalDate.now());
        record.setCreatedAt(LocalDateTime.now());
        record.setDeleted(false);
        return record;
    }

    public static CoreEntity createCoreEntity() {
        CoreEntity entity = new CoreEntity();
        entity.setEntityId("ent_" + UUID.randomUUID());
        entity.setEntityType("resource");
        entity.setStatus("active");
        entity.setAttributes("{\"key\":\"value\"}");
        entity.setCreatedAt(LocalDateTime.now());
        entity.setUpdatedAt(LocalDateTime.now());
        entity.setDeleted(false);
        return entity;
    }

    public static ConfigDefinition createConfigDefinition() {
        ConfigDefinition config = new ConfigDefinition();
        config.setConfigId("cfg_" + UUID.randomUUID());
        config.setNamespace("staging");
        config.setVersion(1);
        config.setParameters("{\"timeout\":30}");
        config.setEnabled(true);
        config.setAppliedAt(LocalDateTime.now());
        config.setCreatedAt(LocalDateTime.now());
        config.setDeleted(false);
        return config;
    }

    public static RunInstance createRunInstance() {
        RunInstance instance = new RunInstance();
        instance.setRunId("run_" + UUID.randomUUID());
        instance.setEntityId("ent_001");
        instance.setPhase("executing");
        instance.setProgress(0.75);
        instance.setStartedAt(LocalDateTime.now());
        instance.setCreatedAt(LocalDateTime.now());
        instance.setDeleted(false);
        return instance;
    }

    public static MetricsSnapshot createMetricsSnapshot() {
        MetricsSnapshot snapshot = new MetricsSnapshot();
        snapshot.setSnapshotId("snap_" + UUID.randomUUID());
        snapshot.setTimestamp(LocalDateTime.now());
        snapshot.setMetrics("{\"throughput\":1500,\"latency_p99\":250}");
        snapshot.setDimensions("{\"host\":\"node-1\"}");
        snapshot.setCreatedAt(LocalDateTime.now());
        snapshot.setDeleted(false);
        return snapshot;
    }

    public static List<Float> createRandomVector(int dimension) {
        List<Float> vector = new ArrayList<>();
        for (int i = 0; i < dimension; i++) {
            vector.add((float) Math.random());
        }
        return vector;
    }

    public static List<Float> createZeroVector(int dimension) {
        List<Float> vector = new ArrayList<>();
        for (int i = 0; i < dimension; i++) {
            vector.add(0.0f);
        }
        return vector;
    }

    public static List<Float> createNormalizedVector(int dimension) {
        List<Float> vector = createRandomVector(dimension);
        double norm = 0;
        for (float v : vector) {
            norm += v * v;
        }
        norm = Math.sqrt(norm);
        List<Float> normalized = new ArrayList<>();
        for (float v : vector) {
            normalized.add((float) (v / norm));
        }
        return normalized;
    }
}
