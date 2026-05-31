package com.streamsql.common;

import com.streamsql.dto.*;
import com.streamsql.fixture.TestBuilders;
import com.streamsql.fixture.TestFixtures;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("测试数据构建器验证测试")
class TestFixtureValidationTest {

    @Nested
    @DisplayName("TestFixtures验证测试")
    class TestFixturesValidation {

        @Test
        @DisplayName("验证质量规则DTO构建")
        void shouldValidateQualityRuleDTO() {
            QualityRuleDTO dto = TestFixtures.createQualityRuleDTO();

            assertNotNull(dto);
            assertNotNull(dto.getRuleName());
            assertNotNull(dto.getRuleType());
            assertNotNull(dto.getDatasourceId());
            assertNotNull(dto.getTableName());
            assertNotNull(dto.getCheckExpression());
            assertTrue(dto.getEnabled());
        }

        @Test
        @DisplayName("验证质量规则DTO空值")
        void shouldValidateQualityRuleDTOWithEmptyValues() {
            QualityRuleDTO dto = TestFixtures.createQualityRuleDTOWithEmptyValues();

            assertNotNull(dto);
            assertEquals("", dto.getRuleName());
            assertEquals("", dto.getRuleType());
        }

        @Test
        @DisplayName("验证质量规则DTO null值")
        void shouldValidateQualityRuleDTOWithNullValues() {
            QualityRuleDTO dto = TestFixtures.createQualityRuleDTOWithNullValues();

            assertNotNull(dto);
            assertNull(dto.getRuleName());
            assertNull(dto.getRuleType());
        }

        @Test
        @DisplayName("验证向量索引DTO构建")
        void shouldValidateVectorIndexDTO() {
            VectorIndexDTO dto = TestFixtures.createVectorIndexDTO();

            assertNotNull(dto);
            assertNotNull(dto.getIndexName());
            assertNotNull(dto.getDatasourceId());
            assertEquals(1536, dto.getVectorDimension());
        }

        @Test
        @DisplayName("验证向量搜索DTO构建")
        void shouldValidateVectorSearchDTO() {
            VectorSearchDTO dto = TestFixtures.createVectorSearchDTO();

            assertNotNull(dto);
            assertNotNull(dto.getVector());
            assertEquals(1536, dto.getVector().size());
            assertEquals(10, dto.getTopK());
        }

        @Test
        @DisplayName("验证流式查询DTO构建")
        void shouldValidateStreamQueryDTO() {
            StreamQueryDTO dto = TestFixtures.createStreamQueryDTO();

            assertNotNull(dto);
            assertNotNull(dto.getSql());
            assertFalse(dto.getSql().isEmpty());
        }

        @Test
        @DisplayName("验证数据源DTO构建")
        void shouldValidateDatasourceDTO() {
            DatasourceDTO dto = TestFixtures.createDatasourceDTO();

            assertNotNull(dto);
            assertNotNull(dto.getDatasourceName());
            assertNotNull(dto.getDatasourceType());
            assertNotNull(dto.getConnectionConfig());
        }

        @Test
        @DisplayName("验证CDC任务DTO构建")
        void shouldValidateCdcTaskDTO() {
            CdcTaskDTO dto = TestFixtures.createCdcTaskDTO();

            assertNotNull(dto);
            assertNotNull(dto.getTaskName());
            assertNotNull(dto.getOutputType());
        }

        @Test
        @DisplayName("验证血缘解析DTO构建")
        void shouldValidateLineageParseDTO() {
            LineageParseDTO dto = TestFixtures.createLineageParseDTO();

            assertNotNull(dto);
            assertNotNull(dto.getSql());
            assertFalse(dto.getSql().isEmpty());
        }

        @Test
        @DisplayName("验证时序数据DTO构建")
        void shouldValidateTimeseriesDataDTO() {
            TimeseriesDataDTO dto = TestFixtures.createTimeseriesDataDTO();

            assertNotNull(dto);
            assertNotNull(dto.getMetricName());
            assertNotNull(dto.getTimestamp());
            assertNotNull(dto.getMetricValue());
        }

        @Test
        @DisplayName("验证生命周期策略DTO构建")
        void shouldValidateLifecyclePolicyDTO() {
            LifecyclePolicyDTO dto = TestFixtures.createLifecyclePolicyDTO();

            assertNotNull(dto);
            assertNotNull(dto.getPolicyName());
            assertEquals(30, dto.getHotStorageDays());
            assertEquals(90, dto.getColdStorageDays());
            assertEquals(365, dto.getArchiveStorageDays());
        }

        @Test
        @DisplayName("验证随机向量生成")
        void shouldValidateRandomVector() {
            List<Float> vector = TestFixtures.createRandomVector(1536);

            assertNotNull(vector);
            assertEquals(1536, vector.size());
            for (Float f : vector) {
                assertNotNull(f);
                assertTrue(f >= 0.0f && f <= 1.0f);
            }
        }

        @Test
        @DisplayName("验证零向量生成")
        void shouldValidateZeroVector() {
            List<Float> vector = TestFixtures.createZeroVector(1536);

            assertNotNull(vector);
            assertEquals(1536, vector.size());
            for (Float f : vector) {
                assertEquals(0.0f, f);
            }
        }

        @Test
        @DisplayName("验证归一化向量生成")
        void shouldValidateNormalizedVector() {
            List<Float> vector = TestFixtures.createNormalizedVector(1536);

            assertNotNull(vector);
            assertEquals(1536, vector.size());

            double norm = 0;
            for (Float f : vector) {
                norm += f * f;
            }
            norm = Math.sqrt(norm);
            assertEquals(1.0, norm, 0.0001);
        }
    }

    @Nested
    @DisplayName("TestBuilders验证测试")
    class TestBuildersValidation {

        @Test
        @DisplayName("验证质量规则DTO Builder")
        void shouldValidateQualityRuleDTOBuilder() {
            QualityRuleDTO dto = TestBuilders.qualityRuleDTO()
                    .ruleName("测试规则")
                    .ruleType("regex")
                    .severity("error")
                    .enabled(true)
                    .build();

            assertNotNull(dto);
            assertEquals("测试规则", dto.getRuleName());
            assertEquals("regex", dto.getRuleType());
            assertEquals("error", dto.getSeverity());
            assertTrue(dto.getEnabled());
        }

        @Test
        @DisplayName("验证质量规则DTO Builder空值")
        void shouldValidateQualityRuleDTOBuilderWithEmptyValues() {
            QualityRuleDTO dto = TestBuilders.qualityRuleDTO()
                    .withEmptyValues()
                    .build();

            assertNotNull(dto);
            assertEquals("", dto.getRuleName());
            assertEquals("", dto.getRuleType());
        }

        @Test
        @DisplayName("验证向量索引DTO Builder")
        void shouldValidateVectorIndexDTOBuilder() {
            VectorIndexDTO dto = TestBuilders.vectorIndexDTO()
                    .indexName("测试索引")
                    .vectorDimension(768)
                    .indexType("hnsw")
                    .build();

            assertNotNull(dto);
            assertEquals("测试索引", dto.getIndexName());
            assertEquals(768, dto.getVectorDimension());
            assertEquals("hnsw", dto.getIndexType());
        }

        @Test
        @DisplayName("验证向量搜索DTO Builder")
        void shouldValidateVectorSearchDTOBuilder() {
            VectorSearchDTO dto = TestBuilders.vectorSearchDTO()
                    .topK(20)
                    .build();

            assertNotNull(dto);
            assertEquals(20, dto.getTopK());
            assertNotNull(dto.getVector());
        }

        @Test
        @DisplayName("验证流式查询DTO Builder")
        void shouldValidateStreamQueryDTOBuilder() {
            StreamQueryDTO dto = TestBuilders.streamQueryDTO()
                    .sql("SELECT * FROM test")
                    .timeout(60000)
                    .maxRecords(500)
                    .build();

            assertNotNull(dto);
            assertEquals("SELECT * FROM test", dto.getSql());
            assertEquals(60000, dto.getTimeout());
            assertEquals(500, dto.getMaxRecords());
        }

        @Test
        @DisplayName("验证数据源DTO Builder")
        void shouldValidateDatasourceDTOBuilder() {
            DatasourceDTO dto = TestBuilders.datasourceDTO()
                    .datasourceName("测试数据源")
                    .datasourceType("postgresql")
                    .description("测试描述")
                    .build();

            assertNotNull(dto);
            assertEquals("测试数据源", dto.getDatasourceName());
            assertEquals("postgresql", dto.getDatasourceType());
            assertEquals("测试描述", dto.getDescription());
        }

        @Test
        @DisplayName("验证CDC任务DTO Builder")
        void shouldValidateCdcTaskDTOBuilder() {
            CdcTaskDTO dto = TestBuilders.cdcTaskDTO()
                    .taskName("测试任务")
                    .outputType("http")
                    .build();

            assertNotNull(dto);
            assertEquals("测试任务", dto.getTaskName());
            assertEquals("http", dto.getOutputType());
        }

        @Test
        @DisplayName("验证生命周期策略DTO Builder")
        void shouldValidateLifecyclePolicyDTOBuilder() {
            LifecyclePolicyDTO dto = TestBuilders.lifecyclePolicyDTO()
                    .policyName("测试策略")
                    .hotStorageDays(7)
                    .coldStorageDays(30)
                    .archiveStorageDays(180)
                    .enabled(false)
                    .build();

            assertNotNull(dto);
            assertEquals("测试策略", dto.getPolicyName());
            assertEquals(7, dto.getHotStorageDays());
            assertEquals(30, dto.getColdStorageDays());
            assertEquals(180, dto.getArchiveStorageDays());
            assertFalse(dto.getEnabled());
        }

        @Test
        @DisplayName("验证时序数据DTO Builder")
        void shouldValidateTimeseriesDataDTOBuilder() {
            TimeseriesDataDTO dto = TestBuilders.timeseriesDataDTO()
                    .metricName("memory_usage")
                    .metricValue(75.5)
                    .build();

            assertNotNull(dto);
            assertEquals("memory_usage", dto.getMetricName());
            assertEquals(75.5, dto.getMetricValue());
        }

        @Test
        @DisplayName("验证血缘解析DTO Builder")
        void shouldValidateLineageParseDTOBuilder() {
            LineageParseDTO dto = TestBuilders.lineageParseDTO()
                    .sql("INSERT INTO target SELECT * FROM source")
                    .build();

            assertNotNull(dto);
            assertEquals("INSERT INTO target SELECT * FROM source", dto.getSql());
        }
    }
}
