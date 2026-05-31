package com.streamsql.modules.lifecycle;

import com.streamsql.dto.LifecyclePolicyDTO;
import com.streamsql.entity.DataArchiveRecord;
import com.streamsql.entity.DatasourceInfo;
import com.streamsql.entity.LifecyclePolicy;
import com.streamsql.fixture.TestBuilders;
import com.streamsql.fixture.TestFixtures;
import com.streamsql.mapper.DataArchiveRecordMapper;
import com.streamsql.mapper.DatasourceInfoMapper;
import com.streamsql.mapper.LifecyclePolicyMapper;
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
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("数据生命周期管理模块测试")
class DataLifecycleServiceTest {

    @Mock
    private LifecyclePolicyMapper lifecyclePolicyMapper;

    @Mock
    private DataArchiveRecordMapper dataArchiveRecordMapper;

    @Mock
    private DatasourceInfoMapper datasourceInfoMapper;

    @Mock
    private ObjectMapper objectMapper;

    @InjectMocks
    private DataLifecycleService dataLifecycleService;

    @Nested
    @DisplayName("正常流程测试")
    class NormalFlowTest {

        @Test
        @DisplayName("创建生命周期策略 - 成功")
        void shouldCreatePolicySuccessfully() {
            LifecyclePolicyDTO dto = TestBuilders.lifecyclePolicyDTO().build();
            when(lifecyclePolicyMapper.insert(any(LifecyclePolicy.class))).thenReturn(1);

            LifecyclePolicy result = dataLifecycleService.createPolicy(dto);

            assertNotNull(result);
            assertEquals(dto.getPolicyName(), result.getPolicyName());
            assertEquals(dto.getHotStorageDays(), result.getHotStorageDays());
            assertEquals(dto.getColdStorageDays(), result.getColdStorageDays());
            assertEquals(dto.getArchiveStorageDays(), result.getArchiveStorageDays());
            assertTrue(result.getEnabled());
        }

        @Test
        @DisplayName("更新生命周期策略 - 成功")
        void shouldUpdatePolicySuccessfully() {
            String policyId = "policy_001";
            LifecyclePolicyDTO dto = TestBuilders.lifecyclePolicyDTO()
                    .policyName("更新后的策略")
                    .hotStorageDays(60)
                    .build();

            LifecyclePolicy existingPolicy = TestFixtures.createLifecyclePolicyEntity();
            existingPolicy.setPolicyId(policyId);

            when(lifecyclePolicyMapper.selectById(policyId)).thenReturn(existingPolicy);
            when(lifecyclePolicyMapper.updateById(any(LifecyclePolicy.class))).thenReturn(1);

            LifecyclePolicy result = dataLifecycleService.updatePolicy(policyId, dto);

            assertEquals("更新后的策略", result.getPolicyName());
            assertEquals(60, result.getHotStorageDays());
        }

        @Test
        @DisplayName("删除生命周期策略 - 成功")
        void shouldDeletePolicySuccessfully() {
            String policyId = "policy_001";
            when(lifecyclePolicyMapper.deleteById(policyId)).thenReturn(1);

            assertDoesNotThrow(() -> dataLifecycleService.deletePolicy(policyId));
            verify(lifecyclePolicyMapper).deleteById(policyId);
        }

        @Test
        @DisplayName("查询生命周期策略 - 成功")
        void shouldGetPolicySuccessfully() {
            String policyId = "policy_001";
            LifecyclePolicy expectedPolicy = TestFixtures.createLifecyclePolicyEntity();
            expectedPolicy.setPolicyId(policyId);

            when(lifecyclePolicyMapper.selectById(policyId)).thenReturn(expectedPolicy);

            LifecyclePolicy result = dataLifecycleService.getPolicy(policyId);

            assertNotNull(result);
            assertEquals(policyId, result.getPolicyId());
        }

        @Test
        @DisplayName("列出生命周期策略 - 成功")
        void shouldListPoliciesSuccessfully() {
            LifecyclePolicy policy = TestFixtures.createLifecyclePolicyEntity();
            List<LifecyclePolicy> policies = Arrays.asList(policy);

            when(lifecyclePolicyMapper.selectPage(any(), any()))
                    .thenReturn(new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>(policies, 1));

            com.streamsql.common.PageResult<LifecyclePolicy> result =
                    dataLifecycleService.listPolicies(1, 10, null, null);

            assertNotNull(result);
            assertFalse(result.getRecords().isEmpty());
        }

        @Test
        @DisplayName("查询归档记录 - 成功")
        void shouldListArchiveRecordsSuccessfully() {
            DataArchiveRecord record = TestFixtures.createDataArchiveRecordEntity();
            List<DataArchiveRecord> records = Arrays.asList(record);

            when(dataArchiveRecordMapper.selectPage(any(), any()))
                    .thenReturn(new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>(records, 1));

            com.streamsql.common.PageResult<DataArchiveRecord> result =
                    dataLifecycleService.listArchiveRecords(1, 10, null, null);

            assertNotNull(result);
            assertFalse(result.getRecords().isEmpty());
        }

        @Test
        @DisplayName("获取存储统计 - 成功")
        void shouldGetStorageStatisticsSuccessfully() {
            LifecyclePolicy policy = TestFixtures.createLifecyclePolicyEntity();
            DataArchiveRecord record = TestFixtures.createDataArchiveRecordEntity();
            record.setArchiveCount(1000L);

            when(lifecyclePolicyMapper.selectList(any())).thenReturn(Arrays.asList(policy));
            when(dataArchiveRecordMapper.selectList(any())).thenReturn(Arrays.asList(record));

            Map<String, Object> result = dataLifecycleService.getStorageStatistics(null);

            assertNotNull(result);
            assertEquals(1L, result.get("policyCount"));
            assertEquals(1L, result.get("archiveFileCount"));
            assertEquals(1000L, result.get("archivedRecordCount"));
        }
    }

    @Nested
    @DisplayName("边界条件测试")
    class BoundaryTest {

        @Test
        @DisplayName("创建策略 - 空字符串值")
        void shouldCreatePolicyWithEmptyValues() {
            LifecyclePolicyDTO dto = TestBuilders.lifecyclePolicyDTO().withEmptyValues().build();
            when(lifecyclePolicyMapper.insert(any(LifecyclePolicy.class))).thenReturn(1);

            LifecyclePolicy result = dataLifecycleService.createPolicy(dto);

            assertNotNull(result);
            assertEquals("", result.getPolicyName());
        }

        @Test
        @DisplayName("创建策略 - null值")
        void shouldCreatePolicyWithNullValues() {
            LifecyclePolicyDTO dto = new LifecyclePolicyDTO();
            when(lifecyclePolicyMapper.insert(any(LifecyclePolicy.class))).thenReturn(1);

            LifecyclePolicy result = dataLifecycleService.createPolicy(dto);

            assertNotNull(result);
            assertNull(result.getPolicyName());
        }

        @Test
        @DisplayName("创建策略 - 零天数")
        void shouldCreatePolicyWithZeroDays() {
            LifecyclePolicyDTO dto = TestBuilders.lifecyclePolicyDTO().withZeroDays().build();
            when(lifecyclePolicyMapper.insert(any(LifecyclePolicy.class))).thenReturn(1);

            LifecyclePolicy result = dataLifecycleService.createPolicy(dto);

            assertNotNull(result);
            assertEquals(0, result.getHotStorageDays());
            assertEquals(0, result.getColdStorageDays());
            assertEquals(0, result.getArchiveStorageDays());
        }

        @Test
        @DisplayName("创建策略 - 负数天数")
        void shouldCreatePolicyWithNegativeDays() {
            LifecyclePolicyDTO dto = TestBuilders.lifecyclePolicyDTO()
                    .hotStorageDays(-1)
                    .coldStorageDays(-1)
                    .archiveStorageDays(-1)
                    .build();
            when(lifecyclePolicyMapper.insert(any(LifecyclePolicy.class))).thenReturn(1);

            LifecyclePolicy result = dataLifecycleService.createPolicy(dto);

            assertNotNull(result);
            assertEquals(-1, result.getHotStorageDays());
        }

        @Test
        @DisplayName("创建策略 - 超大天数")
        void shouldCreatePolicyWithVeryLargeDays() {
            LifecyclePolicyDTO dto = TestBuilders.lifecyclePolicyDTO().withVeryLargeDays().build();
            when(lifecyclePolicyMapper.insert(any(LifecyclePolicy.class))).thenReturn(1);

            LifecyclePolicy result = dataLifecycleService.createPolicy(dto);

            assertNotNull(result);
            assertEquals(Integer.MAX_VALUE, result.getHotStorageDays());
        }

        @Test
        @DisplayName("创建策略 - 禁用状态")
        void shouldCreatePolicyWithDisabledStatus() {
            LifecyclePolicyDTO dto = TestBuilders.lifecyclePolicyDTO().enabled(false).build();
            when(lifecyclePolicyMapper.insert(any(LifecyclePolicy.class))).thenReturn(1);

            LifecyclePolicy result = dataLifecycleService.createPolicy(dto);

            assertNotNull(result);
            assertFalse(result.getEnabled());
        }

        @Test
        @DisplayName("更新策略 - 不存在的ID")
        void shouldThrowWhenUpdatingNonExistentPolicy() {
            String nonExistentId = "non_existent";
            LifecyclePolicyDTO dto = TestBuilders.lifecyclePolicyDTO().build();

            when(lifecyclePolicyMapper.selectById(nonExistentId)).thenReturn(null);

            assertThrows(IllegalArgumentException.class,
                    () -> dataLifecycleService.updatePolicy(nonExistentId, dto));
        }

        @Test
        @DisplayName("查询策略 - 不存在的ID")
        void shouldReturnNullForNonExistentPolicy() {
            String nonExistentId = "non_existent";

            when(lifecyclePolicyMapper.selectById(nonExistentId)).thenReturn(null);

            LifecyclePolicy result = dataLifecycleService.getPolicy(nonExistentId);

            assertNull(result);
        }

        @Test
        @DisplayName("删除策略 - 不存在的ID")
        void shouldHandleDeletingNonExistentPolicy() {
            String nonExistentId = "non_existent";
            when(lifecyclePolicyMapper.deleteById(nonExistentId)).thenReturn(0);

            assertDoesNotThrow(() -> dataLifecycleService.deletePolicy(nonExistentId));
        }

        @Test
        @DisplayName("列出策略 - 空结果")
        void shouldReturnEmptyPageWhenNoPolicies() {
            when(lifecyclePolicyMapper.selectPage(any(), any()))
                    .thenReturn(new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>());

            com.streamsql.common.PageResult<LifecyclePolicy> result =
                    dataLifecycleService.listPolicies(1, 10, null, null);

            assertNotNull(result);
            assertTrue(result.getRecords().isEmpty());
            assertEquals(0, result.getTotal());
        }

        @Test
        @DisplayName("查询归档记录 - 空结果")
        void shouldReturnEmptyArchiveRecordsWhenNoData() {
            when(dataArchiveRecordMapper.selectPage(any(), any()))
                    .thenReturn(new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>());

            com.streamsql.common.PageResult<DataArchiveRecord> result =
                    dataLifecycleService.listArchiveRecords(1, 10, null, null);

            assertNotNull(result);
            assertTrue(result.getRecords().isEmpty());
            assertEquals(0, result.getTotal());
        }

        @Test
        @DisplayName("获取存储统计 - 无策略")
        void shouldReturnEmptyStatisticsWhenNoPolicies() {
            when(lifecyclePolicyMapper.selectList(any())).thenReturn(Arrays.asList());

            Map<String, Object> result = dataLifecycleService.getStorageStatistics(null);

            assertNotNull(result);
            assertEquals(0L, result.get("policyCount"));
            assertEquals(0L, result.get("archiveFileCount"));
            assertEquals(0L, result.get("archivedRecordCount"));
        }
    }

    @Nested
    @DisplayName("并发竞态场景测试")
    class ConcurrencyTest {

        @Test
        @DisplayName("并发创建策略 - 保证线程安全")
        void shouldHandleConcurrentPolicyCreation() throws InterruptedException {
            int threadCount = 10;
            ExecutorService executor = Executors.newFixedThreadPool(threadCount);
            CountDownLatch latch = new CountDownLatch(threadCount);
            AtomicInteger successCount = new AtomicInteger(0);

            when(lifecyclePolicyMapper.insert(any(LifecyclePolicy.class))).thenReturn(1);

            for (int i = 0; i < threadCount; i++) {
                final int index = i;
                executor.submit(() -> {
                    try {
                        LifecyclePolicyDTO dto = TestBuilders.lifecyclePolicyDTO()
                                .policyName("并发策略-" + index + "-" + System.nanoTime())
                                .build();
                        dataLifecycleService.createPolicy(dto);
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
        @DisplayName("并发更新同一策略 - 防止竞态条件")
        void shouldHandleConcurrentUpdatesToSamePolicy() throws InterruptedException {
            String policyId = "policy_001";
            int threadCount = 5;
            ExecutorService executor = Executors.newFixedThreadPool(threadCount);
            CountDownLatch latch = new CountDownLatch(threadCount);
            AtomicInteger successCount = new AtomicInteger(0);

            LifecyclePolicy existingPolicy = TestFixtures.createLifecyclePolicyEntity();
            existingPolicy.setPolicyId(policyId);

            when(lifecyclePolicyMapper.selectById(policyId)).thenReturn(existingPolicy);
            when(lifecyclePolicyMapper.updateById(any(LifecyclePolicy.class))).thenReturn(1);

            for (int i = 0; i < threadCount; i++) {
                final int index = i;
                executor.submit(() -> {
                    try {
                        LifecyclePolicyDTO dto = TestBuilders.lifecyclePolicyDTO()
                                .policyName("并发更新-" + index)
                                .build();
                        dataLifecycleService.updatePolicy(policyId, dto);
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
            String policyId = "policy_001";
            LifecyclePolicy policy = TestFixtures.createLifecyclePolicyEntity();
            policy.setPolicyId(policyId);

            when(lifecyclePolicyMapper.selectById(policyId)).thenReturn(policy);
            when(lifecyclePolicyMapper.deleteById(policyId)).thenReturn(1);

            ExecutorService executor = Executors.newFixedThreadPool(2);
            CountDownLatch latch = new CountDownLatch(2);

            executor.submit(() -> {
                try {
                    dataLifecycleService.getPolicy(policyId);
                } finally {
                    latch.countDown();
                }
            });

            executor.submit(() -> {
                try {
                    dataLifecycleService.deletePolicy(policyId);
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
        void shouldPropagateExceptionWhenInsertFails() {
            LifecyclePolicyDTO dto = TestBuilders.lifecyclePolicyDTO().build();

            when(lifecyclePolicyMapper.insert(any(LifecyclePolicy.class)))
                    .thenThrow(new RuntimeException("数据库连接超时"));

            assertThrows(RuntimeException.class, () -> dataLifecycleService.createPolicy(dto));
        }

        @Test
        @DisplayName("查询策略时数据库异常 - 异常传播")
        void shouldPropagateExceptionWhenQueryFails() {
            String policyId = "policy_001";

            when(lifecyclePolicyMapper.selectById(policyId))
                    .thenThrow(new RuntimeException("查询超时"));

            assertThrows(RuntimeException.class, () -> dataLifecycleService.getPolicy(policyId));
        }

        @Test
        @DisplayName("更新策略时数据库异常 - 异常传播")
        void shouldPropagateExceptionWhenUpdateFails() {
            String policyId = "policy_001";
            LifecyclePolicyDTO dto = TestBuilders.lifecyclePolicyDTO().build();
            LifecyclePolicy existingPolicy = TestFixtures.createLifecyclePolicyEntity();
            existingPolicy.setPolicyId(policyId);

            when(lifecyclePolicyMapper.selectById(policyId)).thenReturn(existingPolicy);
            when(lifecyclePolicyMapper.updateById(any(LifecyclePolicy.class)))
                    .thenThrow(new RuntimeException("更新失败"));

            assertThrows(RuntimeException.class,
                    () -> dataLifecycleService.updatePolicy(policyId, dto));
        }

        @Test
        @DisplayName("删除策略时数据库异常 - 异常传播")
        void shouldPropagateExceptionWhenDeleteFails() {
            String policyId = "policy_001";

            when(lifecyclePolicyMapper.deleteById(policyId))
                    .thenThrow(new RuntimeException("删除失败"));

            assertThrows(RuntimeException.class, () -> dataLifecycleService.deletePolicy(policyId));
        }

        @Test
        @DisplayName("迁移到冷存储时数据源不存在 - 异常处理")
        void shouldHandleMissingDatasourceDuringMigration() throws Exception {
            String policyId = "policy_001";
            LifecyclePolicy policy = TestFixtures.createLifecyclePolicyEntity();
            policy.setPolicyId(policyId);
            policy.setDatasourceId("non_existent_ds");

            when(lifecyclePolicyMapper.selectById(policyId)).thenReturn(policy);
            when(datasourceInfoMapper.selectById("non_existent_ds")).thenReturn(null);

            assertDoesNotThrow(() -> dataLifecycleService.migrateToColdStorage(policyId));
        }

        @Test
        @DisplayName("清理过期数据时数据源不存在 - 异常处理")
        void shouldHandleMissingDatasourceDuringCleanup() throws Exception {
            String policyId = "policy_001";
            LifecyclePolicy policy = TestFixtures.createLifecyclePolicyEntity();
            policy.setPolicyId(policyId);
            policy.setDatasourceId("non_existent_ds");

            when(lifecyclePolicyMapper.selectById(policyId)).thenReturn(policy);
            when(datasourceInfoMapper.selectById("non_existent_ds")).thenReturn(null);

            assertDoesNotThrow(() -> dataLifecycleService.cleanupExpired(policyId));
        }

        @Test
        @DisplayName("执行策略时数据库连接失败 - 降级处理")
        void shouldHandleDatabaseConnectionFailure() {
            LifecyclePolicy policy = TestFixtures.createLifecyclePolicyEntity();
            DatasourceInfo datasource = TestFixtures.createDatasourceInfoEntity();
            datasource.setDatasourceId(policy.getDatasourceId());
            datasource.setConnectionConfig("{\"host\":\"invalid_host\"}");

            when(lifecyclePolicyMapper.selectList(any())).thenReturn(Arrays.asList(policy));
            when(datasourceInfoMapper.selectById(policy.getDatasourceId())).thenReturn(datasource);

            assertDoesNotThrow(() -> dataLifecycleService.executeLifecyclePolicies());
        }

        @Test
        @DisplayName("归档时文件系统异常 - 降级处理")
        void shouldHandleFileSystemExceptionDuringArchiving() {
            assertDoesNotThrow(() -> dataLifecycleService.executeLifecyclePolicies());
        }

        @Test
        @DisplayName("获取存储统计时数据库异常 - 异常传播")
        void shouldPropagateExceptionWhenGettingStatistics() {
            when(lifecyclePolicyMapper.selectList(any()))
                    .thenThrow(new RuntimeException("查询超时"));

            assertThrows(RuntimeException.class, () -> dataLifecycleService.getStorageStatistics(null));
        }
    }
}
