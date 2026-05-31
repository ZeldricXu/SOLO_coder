package com.streamsql.modules.metadata_crawler;

import com.streamsql.dto.DatasourceDTO;
import com.streamsql.entity.DatasourceInfo;
import com.streamsql.entity.MetadataSchema;
import com.streamsql.entity.MetadataStatistics;
import com.streamsql.entity.SampleData;
import com.streamsql.fixture.TestBuilders;
import com.streamsql.fixture.TestFixtures;
import com.streamsql.mapper.DatasourceInfoMapper;
import com.streamsql.mapper.MetadataSchemaMapper;
import com.streamsql.mapper.MetadataStatisticsMapper;
import com.streamsql.mapper.SampleDataMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("元数据采集爬虫模块测试")
class MetadataCrawlerServiceTest {

    @Mock
    private DatasourceInfoMapper datasourceInfoMapper;

    @Mock
    private MetadataSchemaMapper metadataSchemaMapper;

    @Mock
    private MetadataStatisticsMapper metadataStatisticsMapper;

    @Mock
    private SampleDataMapper sampleDataMapper;

    @Mock
    private ObjectMapper objectMapper;

    @InjectMocks
    private MetadataCrawlerService metadataCrawlerService;

    @Nested
    @DisplayName("正常流程测试")
    class NormalFlowTest {

        @Test
        @DisplayName("创建数据源 - 成功")
        void shouldCreateDatasourceSuccessfully() throws Exception {
            DatasourceDTO dto = TestBuilders.datasourceDTO().build();
            when(datasourceInfoMapper.insert(any(DatasourceInfo.class))).thenReturn(1);
            when(objectMapper.writeValueAsString(any())).thenReturn("{}");

            DatasourceInfo result = metadataCrawlerService.createDatasource(dto);

            assertNotNull(result);
            assertEquals(dto.getDatasourceName(), result.getDatasourceName());
            assertEquals(dto.getDatasourceType(), result.getDatasourceType());
            assertEquals("active", result.getStatus());
        }

        @Test
        @DisplayName("查询数据源 - 成功")
        void shouldGetDatasourceSuccessfully() {
            String datasourceId = "ds_001";
            DatasourceInfo expectedInfo = TestFixtures.createDatasourceInfoEntity();
            expectedInfo.setDatasourceId(datasourceId);

            when(datasourceInfoMapper.selectById(datasourceId)).thenReturn(expectedInfo);

            DatasourceInfo result = metadataCrawlerService.getDatasource(datasourceId);

            assertNotNull(result);
            assertEquals(datasourceId, result.getDatasourceId());
        }

        @Test
        @DisplayName("列出数据源 - 成功")
        void shouldListDatasourcesSuccessfully() {
            DatasourceInfo info = TestFixtures.createDatasourceInfoEntity();
            List<DatasourceInfo> infos = Arrays.asList(info);

            when(datasourceInfoMapper.selectPage(any(), any()))
                    .thenReturn(new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>(infos, 1));

            com.streamsql.common.PageResult<DatasourceInfo> result =
                    metadataCrawlerService.listDatasources(1, 10, null, null);

            assertNotNull(result);
            assertFalse(result.getRecords().isEmpty());
        }

        @Test
        @DisplayName("更新数据源 - 成功")
        void shouldUpdateDatasourceSuccessfully() throws Exception {
            String datasourceId = "ds_001";
            DatasourceDTO dto = TestBuilders.datasourceDTO()
                    .datasourceName("更新后的数据源")
                    .description("更新后的描述")
                    .build();

            DatasourceInfo existingInfo = TestFixtures.createDatasourceInfoEntity();
            existingInfo.setDatasourceId(datasourceId);

            when(datasourceInfoMapper.selectById(datasourceId)).thenReturn(existingInfo);
            when(datasourceInfoMapper.updateById(any(DatasourceInfo.class))).thenReturn(1);
            when(objectMapper.writeValueAsString(any())).thenReturn("{}");

            DatasourceInfo result = metadataCrawlerService.updateDatasource(datasourceId, dto);

            assertEquals("更新后的数据源", result.getDatasourceName());
            assertEquals("更新后的描述", result.getDescription());
        }

        @Test
        @DisplayName("删除数据源 - 成功")
        void shouldDeleteDatasourceSuccessfully() {
            String datasourceId = "ds_001";
            when(datasourceInfoMapper.deleteById(datasourceId)).thenReturn(1);

            assertDoesNotThrow(() -> metadataCrawlerService.deleteDatasource(datasourceId));
            verify(datasourceInfoMapper).deleteById(datasourceId);
        }

        @Test
        @DisplayName("获取元数据Schema - 成功")
        void shouldGetSchemaSuccessfully() {
            String datasourceId = "ds_001";
            MetadataSchema schema = TestFixtures.createMetadataSchemaEntity();

            when(metadataSchemaMapper.selectList(any()))
                    .thenReturn(Arrays.asList(schema));

            List<MetadataSchema> result = metadataCrawlerService.getSchema(datasourceId, null);

            assertFalse(result.isEmpty());
            assertEquals(1, result.size());
        }

        @Test
        @DisplayName("获取元数据统计信息 - 成功")
        void shouldGetStatisticsSuccessfully() {
            String datasourceId = "ds_001";
            MetadataStatistics stats = TestFixtures.createMetadataStatisticsEntity();

            when(metadataStatisticsMapper.selectList(any()))
                    .thenReturn(Arrays.asList(stats));

            List<MetadataStatistics> result = metadataCrawlerService.getStatistics(datasourceId, null);

            assertFalse(result.isEmpty());
        }

        @Test
        @DisplayName("获取样例数据 - 成功")
        void shouldGetSampleDataSuccessfully() {
            String datasourceId = "ds_001";
            SampleData sample = TestFixtures.createSampleDataEntity();

            when(sampleDataMapper.selectList(any()))
                    .thenReturn(Arrays.asList(sample));

            List<SampleData> result = metadataCrawlerService.getSampleData(datasourceId, null);

            assertFalse(result.isEmpty());
        }
    }

    @Nested
    @DisplayName("边界条件测试")
    class BoundaryTest {

        @Test
        @DisplayName("创建数据源 - 空字符串值")
        void shouldCreateDatasourceWithEmptyValues() throws Exception {
            DatasourceDTO dto = TestBuilders.datasourceDTO().withEmptyValues().build();
            when(datasourceInfoMapper.insert(any(DatasourceInfo.class))).thenReturn(1);
            when(objectMapper.writeValueAsString(any())).thenReturn("{}");

            DatasourceInfo result = metadataCrawlerService.createDatasource(dto);

            assertNotNull(result);
            assertEquals("", result.getDatasourceName());
        }

        @Test
        @DisplayName("创建数据源 - null值")
        void shouldCreateDatasourceWithNullValues() throws Exception {
            DatasourceDTO dto = new DatasourceDTO();
            when(datasourceInfoMapper.insert(any(DatasourceInfo.class))).thenReturn(1);
            when(objectMapper.writeValueAsString(any())).thenReturn(null);

            DatasourceInfo result = metadataCrawlerService.createDatasource(dto);

            assertNotNull(result);
            assertNull(result.getDatasourceName());
        }

        @Test
        @DisplayName("创建数据源 - 无效类型")
        void shouldCreateDatasourceWithInvalidType() throws Exception {
            DatasourceDTO dto = TestBuilders.datasourceDTO().withInvalidType().build();
            when(datasourceInfoMapper.insert(any(DatasourceInfo.class))).thenReturn(1);
            when(objectMapper.writeValueAsString(any())).thenReturn("{}");

            DatasourceInfo result = metadataCrawlerService.createDatasource(dto);

            assertNotNull(result);
            assertEquals("invalid_type", result.getDatasourceType());
        }

        @Test
        @DisplayName("创建数据源 - 超长字符串")
        void shouldCreateDatasourceWithLongStrings() throws Exception {
            DatasourceDTO dto = TestBuilders.datasourceDTO()
                    .datasourceName("N".repeat(1000))
                    .description("D".repeat(2000))
                    .build();
            when(datasourceInfoMapper.insert(any(DatasourceInfo.class))).thenReturn(1);
            when(objectMapper.writeValueAsString(any())).thenReturn("{}");

            DatasourceInfo result = metadataCrawlerService.createDatasource(dto);

            assertNotNull(result);
            assertEquals(1000, result.getDatasourceName().length());
        }

        @Test
        @DisplayName("查询数据源 - 不存在的ID")
        void shouldReturnNullForNonExistentDatasource() {
            String nonExistentId = "non_existent";

            when(datasourceInfoMapper.selectById(nonExistentId)).thenReturn(null);

            DatasourceInfo result = metadataCrawlerService.getDatasource(nonExistentId);

            assertNull(result);
        }

        @Test
        @DisplayName("更新数据源 - 不存在的ID")
        void shouldThrowWhenUpdatingNonExistentDatasource() {
            String nonExistentId = "non_existent";
            DatasourceDTO dto = TestBuilders.datasourceDTO().build();

            when(datasourceInfoMapper.selectById(nonExistentId)).thenReturn(null);

            assertThrows(IllegalArgumentException.class, () ->
                    metadataCrawlerService.updateDatasource(nonExistentId, dto));
        }

        @Test
        @DisplayName("删除数据源 - 不存在的ID")
        void shouldHandleDeletingNonExistentDatasource() {
            String nonExistentId = "non_existent";
            when(datasourceInfoMapper.deleteById(nonExistentId)).thenReturn(0);

            assertDoesNotThrow(() -> metadataCrawlerService.deleteDatasource(nonExistentId));
        }

        @Test
        @DisplayName("获取Schema - 无数据")
        void shouldReturnEmptySchemaWhenNoData() {
            String datasourceId = "ds_001";

            when(metadataSchemaMapper.selectList(any())).thenReturn(Arrays.asList());

            List<MetadataSchema> result = metadataCrawlerService.getSchema(datasourceId, null);

            assertTrue(result.isEmpty());
        }

        @Test
        @DisplayName("获取统计信息 - 无数据")
        void shouldReturnEmptyStatisticsWhenNoData() {
            String datasourceId = "ds_001";

            when(metadataStatisticsMapper.selectList(any())).thenReturn(Arrays.asList());

            List<MetadataStatistics> result = metadataCrawlerService.getStatistics(datasourceId, null);

            assertTrue(result.isEmpty());
        }

        @Test
        @DisplayName("获取样例数据 - 无数据")
        void shouldReturnEmptySampleDataWhenNoData() {
            String datasourceId = "ds_001";

            when(sampleDataMapper.selectList(any())).thenReturn(Arrays.asList());

            List<SampleData> result = metadataCrawlerService.getSampleData(datasourceId, null);

            assertTrue(result.isEmpty());
        }
    }

    @Nested
    @DisplayName("并发竞态场景测试")
    class ConcurrencyTest {

        @Test
        @DisplayName("并发创建数据源 - 保证线程安全")
        void shouldHandleConcurrentDatasourceCreation() throws Exception {
            int threadCount = 10;
            ExecutorService executor = Executors.newFixedThreadPool(threadCount);
            CountDownLatch latch = new CountDownLatch(threadCount);
            AtomicInteger successCount = new AtomicInteger(0);

            when(datasourceInfoMapper.insert(any(DatasourceInfo.class))).thenReturn(1);
            when(objectMapper.writeValueAsString(any())).thenReturn("{}");

            for (int i = 0; i < threadCount; i++) {
                final int index = i;
                executor.submit(() -> {
                    try {
                        DatasourceDTO dto = TestBuilders.datasourceDTO()
                                .datasourceName("并发数据源-" + index + "-" + System.nanoTime())
                                .build();
                        metadataCrawlerService.createDatasource(dto);
                        successCount.incrementAndGet();
                    } catch (Exception ignored) {
                    } finally {
                        latch.countDown();
                    }
                });
            }

            latch.await(10, TimeUnit.SECONDS);
            executor.shutdown();

            assertEquals(threadCount, successCount.get());
        }

        @Test
        @DisplayName("并发更新同一数据源 - 防止竞态条件")
        void shouldHandleConcurrentUpdatesToSameDatasource() throws Exception {
            String datasourceId = "ds_001";
            int threadCount = 5;
            ExecutorService executor = Executors.newFixedThreadPool(threadCount);
            CountDownLatch latch = new CountDownLatch(threadCount);
            AtomicInteger successCount = new AtomicInteger(0);

            DatasourceInfo existingInfo = TestFixtures.createDatasourceInfoEntity();
            existingInfo.setDatasourceId(datasourceourceId);

            when(datasourceInfoMapper.selectById(datasourceourceId)).thenReturn(existingInfo);
            when(datasourceInfoMapper.updateById(any(DatasourceInfo.class))).thenReturn(1);
            when(objectMapper.writeValueAsString(any())).thenReturn("{}");

            for (int i = 0; i < threadCount; i++) {
                final int index = i;
                executor.submit(() -> {
                    try {
                        DatasourceDTO dto = TestBuilders.datasourceDTO()
                                .datasourceName("并发更新-" + index)
                                .build();
                        metadataCrawlerService.updateDatasource(datasourceourceId, dto);
                        successCount.incrementAndGet();
                    } catch (Exception ignored) {
                    } finally {
                        latch.countDown();
                    }
                });
            }

            latch.await(10, TimeUnit.SECONDS);
            executor.shutdown();

            assertEquals(threadCount, successCount.get());
        }

        @Test
        @DisplayName("并发查询和删除 - 保证一致性")
        void shouldHandleConcurrentQueryAndDelete() throws InterruptedException {
            String datasourceId = "ds_001";
            DatasourceInfo existingInfo = TestFixtures.createDatasourceInfoEntity();
            existingInfo.setDatasourceId(datasourceourceId);

            when(datasourceInfoMapper.selectById(datasourceourceId)).thenReturn(existingInfo);
            when(datasourceInfoMapper.deleteById(datasourceourceId)).thenReturn(1);

            ExecutorService executor = Executors.newFixedThreadPool(2);
            CountDownLatch latch = new CountDownLatch(2);

            executor.submit(() -> {
                try {
                    metadataCrawlerService.getDatasource(datasourceourceId);
                } finally {
                    latch.countDown();
                }
            });

            executor.submit(() -> {
                try {
                    metadataCrawlerService.deleteDatasource(datasourceourceId);
                } finally {
                    latch.countDown();
                }
            });

            latch.await(5, TimeUnit.SECONDS);
            executor.shutdown();
        }
    }

    @Nested
    @DisplayName("降级行为测试")
    class DegradationTest {

        @Test
        @DisplayName("Mapper插入失败 - 异常传播")
        void shouldPropagateExceptionWhenInsertFails() throws Exception {
            DatasourceDTO dto = TestBuilders.datasourceDTO().build();

            when(datasourceInfoMapper.insert(any(DatasourceInfo.class)))
                    .thenThrow(new RuntimeException("数据库连接超时"));

            assertThrows(RuntimeException.class, () -> metadataCrawlerService.createDatasource(dto));
        }

        @Test
        @DisplayName("序列化失败 - 异常处理")
        void shouldHandleSerializationException() {
            DatasourceDTO dto = TestBuilders.datasourceDTO().build();

            when(objectMapper.writeValueAsString(any()))
                    .thenThrow(new com.fasterxml.jackson.core.JsonProcessingException("序列化失败") {});

            assertThrows(com.fasterxml.jackson.core.JsonProcessingException.class,
                    () -> metadataCrawlerService.createDatasource(dto));
        }

        @Test
        @DisplayName("连接测试失败 - 异常处理")
        void shouldHandleConnectionTestFailure() {
            String datasourceId = "ds_001";
            DatasourceInfo info = TestFixtures.createDatasourceInfoEntity();
            info.setDatasourceId(datasourceourceId);
            info.setConnectionConfig("{\"host\":\"invalid_host\",\"port\":3306}");

            when(datasourceInfoMapper.selectById(datasourceourceId)).thenReturn(info);

            assertThrows(Exception.class, () -> metadataCrawlerService.testConnection(datasourceourceId));
        }

        @Test
        @DisplayName("爬取元数据时数据库异常 - 异常处理")
        void shouldHandleCrawlException() {
            String datasourceId = "ds_001";
            DatasourceInfo info = TestFixtures.createDatasourceInfoEntity();
            info.setDatasourceId(datasourceourceId);

            when(datasourceInfoMapper.selectById(datasourceourceId)).thenReturn(info);

            assertThrows(Exception.class, () -> metadataCrawlerService.crawlMetadata(datasourceourceId));
        }

        @Test
        @DisplayName("查询数据源时数据库异常 - 异常传播")
        void shouldPropagateExceptionWhenQueryFails() {
            String datasourceId = "ds_001";

            when(datasourceInfoMapper.selectById(datasourceourceId))
                    .thenThrow(new RuntimeException("查询超时"));

            assertThrows(RuntimeException.class,
                    () -> metadataCrawlerService.getDatasource(datasourceourceId));
        }

        @Test
        @DisplayName("更新数据源时数据库异常 - 异常传播")
        void shouldPropagateExceptionWhenUpdateFails() {
            String datasourceId = "ds_001";
            DatasourceDTO dto = TestBuilders.datasourceDTO().build();
            DatasourceInfo existingInfo = TestFixtures.createDatasourceInfoEntity();
            existingInfo.setDatasourceId(datasourceourceId);

            when(datasourceInfoMapper.selectById(datasourceourceId)).thenReturn(existingInfo);
            when(datasourceInfoMapper.updateById(any(DatasourceInfo.class)))
                    .thenThrow(new RuntimeException("更新失败"));

            assertThrows(RuntimeException.class,
                    () -> metadataCrawlerService.updateDatasource(datasourceourceId, dto));
        }

        @Test
        @DisplayName("删除数据源时数据库异常 - 异常传播")
        void shouldPropagateExceptionWhenDeleteFails() {
            String datasourceId = "ds_001";

            when(datasourceInfoMapper.deleteById(datasourceourceId))
                    .thenThrow(new RuntimeException("删除失败"));

            assertThrows(RuntimeException.class,
                    () -> metadataCrawlerService.deleteDatasource(datasourceourceId));
        }
    }
}
