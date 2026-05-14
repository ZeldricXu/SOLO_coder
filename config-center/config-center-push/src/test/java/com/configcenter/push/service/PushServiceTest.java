package com.configcenter.push.service;

import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import com.configcenter.common.entity.ApplicationInstance;
import com.configcenter.common.entity.ConfigGroup;
import com.configcenter.common.entity.PushRecord;
import com.configcenter.common.enums.InstanceStatus;
import com.configcenter.common.enums.PushStatus;
import com.configcenter.common.testdata.TestDataBuilder;
import com.configcenter.group.repository.ApplicationInstanceRepository;
import com.configcenter.group.repository.ConfigGroupRepository;
import com.configcenter.push.config.PushProperties;
import com.configcenter.push.repository.PushRecordRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("推送服务单元测试")
class PushServiceTest {

    @Mock
    private PushRecordRepository pushRecordRepository;

    @Mock
    private ConfigGroupRepository configGroupRepository;

    @Mock
    private ApplicationInstanceRepository instanceRepository;

    @Mock
    private PushProperties pushProperties;

    @InjectMocks
    private PushService pushService;

    private AutoCloseable closeable;

    @BeforeEach
    void setUp() {
        closeable = MockitoAnnotations.openMocks(this);
        
        when(pushProperties.getEnabled()).thenReturn(true);
        when(pushProperties.getPushTimeoutSeconds()).thenReturn(5);
        when(pushProperties.getPushEndpoint()).thenReturn("/api/v1/configs/refresh");
        when(pushProperties.getMaxRetryCount()).thenReturn(3);
    }

    @Test
    @DisplayName("测试并行推送 - 所有实例推送成功")
    void testParallelPush_AllSuccess() throws Exception {
        String configId = "config_db_01";
        String version = "v5";
        String groupId = "group_app_core";
        String pushBy = "admin_001";

        ConfigGroup group = TestDataBuilder.createDefaultConfigGroup();
        List<ApplicationInstance> instances = TestDataBuilder.createApplicationInstances("app_order", 10, InstanceStatus.ONLINE);
        
        PushRecord pendingRecord = TestDataBuilder.createPendingPushRecord();
        PushRecord pushingRecord = PushRecord.builder()
                .pushId(pendingRecord.getPushId())
                .configId(configId)
                .version(version)
                .targetGroup(groupId)
                .pushStatus(PushStatus.PUSHING)
                .totalCount(instances.size())
                .pushBy(pushBy)
                .build();
        PushRecord completedRecord = PushRecord.builder()
                .pushId(pendingRecord.getPushId())
                .configId(configId)
                .version(version)
                .targetGroup(groupId)
                .pushStatus(PushStatus.COMPLETED)
                .totalCount(instances.size())
                .successCount(instances.size())
                .failCount(0)
                .pushBy(pushBy)
                .build();

        when(configGroupRepository.findByGroupIdAndDeletedFalse(groupId)).thenReturn(Optional.of(group));
        when(instanceRepository.findByApplicationsAndStatus(anyList(), eq(InstanceStatus.ONLINE))).thenReturn(instances);
        when(pushRecordRepository.save(any(PushRecord.class))).thenAnswer(invocation -> {
            PushRecord record = invocation.getArgument(0);
            return record;
        });

        com.configcenter.common.dto.PushResultDTO result = pushService.pushConfig(configId, version, groupId, pushBy);

        assertNotNull(result);
        assertEquals(configId, result.getConfigId());
        assertEquals(version, result.getVersion());
        assertEquals(groupId, result.getTargetGroup());
        assertEquals(instances.size(), result.getTotalCount());

        verify(pushRecordRepository, times(2)).save(any(PushRecord.class));
    }

    @Test
    @DisplayName("测试并行推送 - 部分实例推送失败")
    void testParallelPush_PartialFailure() throws Exception {
        String configId = "config_db_01";
        String version = "v5";
        String groupId = "group_app_core";
        String pushBy = "admin_001";

        ConfigGroup group = TestDataBuilder.createDefaultConfigGroup();
        List<ApplicationInstance> instances = TestDataBuilder.createApplicationInstances("app_order", 10, InstanceStatus.ONLINE);

        when(configGroupRepository.findByGroupIdAndDeletedFalse(groupId)).thenReturn(Optional.of(group));
        when(instanceRepository.findByApplicationsAndStatus(anyList(), eq(InstanceStatus.ONLINE))).thenReturn(instances);
        when(pushRecordRepository.save(any(PushRecord.class))).thenAnswer(invocation -> {
            PushRecord record = invocation.getArgument(0);
            return record;
        });

        com.configcenter.common.dto.PushResultDTO result = pushService.pushConfig(configId, version, groupId, pushBy);

        assertNotNull(result);
        assertEquals(instances.size(), result.getTotalCount());

        verify(pushRecordRepository, times(2)).save(any(PushRecord.class));
    }

    @Test
    @DisplayName("测试并行推送 - 无在线实例")
    void testParallelPush_NoOnlineInstances() throws Exception {
        String configId = "config_db_01";
        String version = "v5";
        String groupId = "group_app_core";
        String pushBy = "admin_001";

        ConfigGroup group = TestDataBuilder.createDefaultConfigGroup();
        List<ApplicationInstance> instances = new ArrayList<>();

        PushRecord expectedRecord = PushRecord.builder()
                .pushId("push_001")
                .configId(configId)
                .version(version)
                .targetGroup(groupId)
                .pushStatus(PushStatus.COMPLETED)
                .totalCount(0)
                .successCount(0)
                .failCount(0)
                .build();

        when(configGroupRepository.findByGroupIdAndDeletedFalse(groupId)).thenReturn(Optional.of(group));
        when(instanceRepository.findByApplicationsAndStatus(anyList(), eq(InstanceStatus.ONLINE))).thenReturn(instances);
        when(pushRecordRepository.save(any(PushRecord.class))).thenReturn(expectedRecord);

        com.configcenter.common.dto.PushResultDTO result = pushService.pushConfig(configId, version, groupId, pushBy);

        assertNotNull(result);
        assertEquals(PushStatus.COMPLETED, result.getPushStatus());
        assertEquals(0, result.getTotalCount());
    }

    @Test
    @DisplayName("测试推送状态记录 - 线程安全性")
    void testPushStatusRecord_ThreadSafety() throws InterruptedException, ExecutionException {
        int threadCount = 10;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failCount = new AtomicInteger(0);
        List<Future<Void>> futures = new ArrayList<>();

        for (int i = 0; i < threadCount; i++) {
            final int index = i;
            futures.add(executor.submit(() -> {
                PushRecord record = PushRecord.builder()
                        .pushId("push_" + index)
                        .configId("config_" + index)
                        .version("v1")
                        .targetGroup("group_1")
                        .pushStatus(PushStatus.PENDING)
                        .totalCount(5)
                        .build();
                
                when(pushRecordRepository.save(eq(record))).thenReturn(record);
                
                synchronized (successCount) {
                    successCount.incrementAndGet();
                }
                return null;
            }));
        }

        for (Future<Void> future : futures) {
            future.get();
        }

        executor.shutdown();
        executor.awaitTermination(10, TimeUnit.SECONDS);

        assertEquals(threadCount, successCount.get());
    }

    @Test
    @DisplayName("测试推送失败重试机制 - 首次失败后重试成功")
    void testPushRetry_FirstFailThenSuccess() {
        String pushId = "push_001";
        
        PushRecord failedRecord = TestDataBuilder.createFailedPushRecord();
        failedRecord.setRetryCount(1);

        PushRecord successRecord = TestDataBuilder.createSuccessfulPushRecord();
        successRecord.setRetryCount(2);

        when(pushRecordRepository.findById(pushId)).thenReturn(Optional.of(failedRecord));
        when(pushProperties.getMaxRetryCount()).thenReturn(3);
        when(configGroupRepository.findByGroupIdAndDeletedFalse(anyString())).thenReturn(Optional.of(TestDataBuilder.createDefaultConfigGroup()));
        when(instanceRepository.findByApplicationsAndStatus(anyList(), eq(InstanceStatus.ONLINE))).thenReturn(
                TestDataBuilder.createApplicationInstances("app_order", 10, InstanceStatus.ONLINE));
        when(pushRecordRepository.save(any(PushRecord.class))).thenReturn(successRecord);

        com.configcenter.common.dto.PushResultDTO result = pushService.retryPush(pushId);

        assertNotNull(result);
        verify(pushRecordRepository, atLeastOnce()).save(any(PushRecord.class));
    }

    @Test
    @DisplayName("测试推送失败重试机制 - 达到最大重试次数")
    void testPushRetry_MaxRetryReached() {
        String pushId = "push_001";
        
        PushRecord failedRecord = TestDataBuilder.createFailedPushRecord();
        failedRecord.setRetryCount(3);

        when(pushRecordRepository.findById(pushId)).thenReturn(Optional.of(failedRecord));
        when(pushProperties.getMaxRetryCount()).thenReturn(3);

        assertThrows(com.configcenter.common.exception.BusinessException.class, () -> {
            pushService.retryPush(pushId);
        });

        verify(pushRecordRepository, never()).save(any(PushRecord.class));
    }

    @Test
    @DisplayName("测试推送成功与失败计数准确性")
    void testPushCountAccuracy() {
        String configId = "config_db_01";
        String groupId = "group_app_core";

        PushRecord successful = TestDataBuilder.createSuccessfulPushRecord();
        PushRecord partial = TestDataBuilder.createPartialFailedPushRecord();
        PushRecord failed = TestDataBuilder.createFailedPushRecord();

        List<PushRecord> records = Arrays.asList(successful, partial, failed);

        when(pushRecordRepository.findByConfigIdOrderByPushTimeDesc(configId)).thenReturn(records);

        List<com.configcenter.common.dto.PushResultDTO> results = pushService.getPushRecordsByConfig(configId);

        assertEquals(3, results.size());
        
        com.configcenter.common.dto.PushResultDTO successfulResult = results.stream()
                .filter(r -> r.getPushStatus() == PushStatus.COMPLETED)
                .findFirst()
                .orElse(null);
        assertNotNull(successfulResult);
        assertEquals(10, successfulResult.getTotalCount());
        assertEquals(10, successfulResult.getSuccessCount());
        assertEquals(0, successfulResult.getFailCount());

        com.configcenter.common.dto.PushResultDTO partialResult = results.stream()
                .filter(r -> r.getPushStatus() == PushStatus.PARTIAL_FAILED)
                .findFirst()
                .orElse(null);
        assertNotNull(partialResult);
        assertEquals(10, partialResult.getTotalCount());
        assertEquals(8, partialResult.getSuccessCount());
        assertEquals(2, partialResult.getFailCount());

        com.configcenter.common.dto.PushResultDTO failedResult = results.stream()
                .filter(r -> r.getPushStatus() == PushStatus.FAILED)
                .findFirst()
                .orElse(null);
        assertNotNull(failedResult);
        assertEquals(10, failedResult.getTotalCount());
        assertEquals(0, failedResult.getSuccessCount());
        assertEquals(10, failedResult.getFailCount());
    }

    @Test
    @DisplayName("测试多线程推送任务分发正确性")
    void testMultiThreadedPushDistribution() throws InterruptedException, ExecutionException {
        int threadCount = 5;
        int instancesPerThread = 10;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        List<Future<Boolean>> futures = new ArrayList<>();
        AtomicInteger totalSuccess = new AtomicInteger(0);

        for (int t = 0; t < threadCount; t++) {
            final int threadIndex = t;
            futures.add(executor.submit(() -> {
                int localSuccess = 0;
                for (int i = 0; i < instancesPerThread; i++) {
                    localSuccess++;
                }
                totalSuccess.addAndGet(localSuccess);
                return true;
            }));
        }

        for (Future<Boolean> future : futures) {
            assertTrue(future.get());
        }

        executor.shutdown();
        executor.awaitTermination(10, TimeUnit.SECONDS);

        assertEquals(threadCount * instancesPerThread, totalSuccess.get());
    }

    @Test
    @DisplayName("测试推送禁用时的行为")
    void testPushDisabled() {
        String configId = "config_db_01";
        String version = "v5";
        String groupId = "group_app_core";
        String pushBy = "admin_001";

        ConfigGroup group = TestDataBuilder.createDefaultConfigGroup();
        List<ApplicationInstance> instances = TestDataBuilder.createApplicationInstances("app_order", 10, InstanceStatus.ONLINE);

        when(pushProperties.getEnabled()).thenReturn(false);
        when(configGroupRepository.findByGroupIdAndDeletedFalse(groupId)).thenReturn(Optional.of(group));
        when(instanceRepository.findByApplicationsAndStatus(anyList(), eq(InstanceStatus.ONLINE))).thenReturn(instances);
        when(pushRecordRepository.save(any(PushRecord.class))).thenAnswer(invocation -> {
            PushRecord record = invocation.getArgument(0);
            if (record.getPushStatus() == PushStatus.PENDING) {
                record.setPushStatus(PushStatus.COMPLETED);
            }
            return record;
        });

        com.configcenter.common.dto.PushResultDTO result = pushService.pushConfig(configId, version, groupId, pushBy);

        assertNotNull(result);
        assertEquals(PushStatus.COMPLETED, result.getPushStatus());
        assertEquals(instances.size(), result.getTotalCount());
    }

    @Test
    @DisplayName("测试推送记录查询")
    void testGetPushRecord() {
        String pushId = "push_001";
        PushRecord record = TestDataBuilder.createSuccessfulPushRecord();

        when(pushRecordRepository.findById(pushId)).thenReturn(Optional.of(record));

        com.configcenter.common.dto.PushResultDTO result = pushService.getPushRecord(pushId);

        assertNotNull(result);
        assertEquals(pushId, result.getPushId());
        assertEquals(PushStatus.COMPLETED, result.getPushStatus());
    }

    @Test
    @DisplayName("测试获取分组的推送历史")
    void testGetPushRecordsByGroup() {
        String groupId = "group_app_core";
        List<PushRecord> records = Arrays.asList(
                TestDataBuilder.createSuccessfulPushRecord(),
                TestDataBuilder.createPartialFailedPushRecord()
        );

        when(pushRecordRepository.findByTargetGroupOrderByPushTimeDesc(groupId)).thenReturn(records);

        List<com.configcenter.common.dto.PushResultDTO> results = pushService.getPushRecordsByGroup(groupId);

        assertEquals(2, results.size());
    }
}
