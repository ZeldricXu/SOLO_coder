package com.datamigrate.service;

import com.datamigrate.builder.TestDataBuilder;
import com.datamigrate.common.FailStatus;
import com.datamigrate.entity.FailRecord;
import com.datamigrate.repository.FailRecordRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("失败处理服务测试")
class FailServiceTest {

    @Mock
    private FailRecordRepository failRecordRepository;

    @Mock
    private LogService logService;

    @InjectMocks
    private FailService failService;

    private static final String TASK_ID = "test_fail_001";

    @Test
    @DisplayName("记录失败 - 首次失败记录")
    void recordFail_FirstTime_ShouldCreatePendingRetry() {
        Map<String, Object> recordData = TestDataBuilder.createSourceRecord(1L, "Test", "test@test.com");
        when(failRecordRepository.save(any(FailRecord.class)).thenAnswer(invocation -> invocation.getArgument(0));

        FailRecord result = failService.recordFail(TASK_ID, "key_1", recordData, "连接超时", 3);

        assertNotNull(result);
        assertEquals(TASK_ID, result.getTaskId());
        assertEquals("key_1", result.getRecordKey());
        assertEquals(0, result.getRetryCount());
        assertEquals(3, result.getMaxRetryTimes());
        assertEquals(FailStatus.PENDING_RETRY, result.getStatus());
        assertNotNull(result.getNextRetryAt());

        verify(failRecordRepository).save(any(FailRecord.class));
    }

    @Test
    @DisplayName("重试成功 - 更新状态为成功")
    void updateRetryResult_Success_ShouldUpdateToSuccess() {
        FailRecord record = TestDataBuilder.createFailRecord(TASK_ID, "key_1", 3);
        record.setRetryCount(1);

        when(failRecordRepository.save(any(FailRecord.class)).thenAnswer(invocation -> invocation.getArgument(0)));

        boolean result = failService.updateRetryResult(record, true);

        assertTrue(result);
        assertEquals(FailStatus.SUCCESS, record.getStatus());
        assertEquals(2, record.getRetryCount());
        assertNotNull(record.getLastRetryAt());
    }

    @Test
    @DisplayName("重试失败但未达上限 - 更新状态为待重试")
    void updateRetryResult_FailureUnderLimit_ShouldStayPending() {
        FailRecord record = TestDataBuilder.createFailRecord(TASK_ID, "key_2", 3);
        record.setRetryCount(1);

        when(failRecordRepository.save(any(FailRecord.class)).thenAnswer(invocation -> invocation.getArgument(0)));

        boolean result = failService.updateRetryResult(record, false);

        assertFalse(result);
        assertEquals(FailStatus.PENDING_RETRY, record.getStatus());
        assertEquals(2, record.getRetryCount());
    }

    @Test
    @DisplayName("重试失败达上限 - 更新状态为最终失败")
    void updateRetryResult_FailureAtLimit_ShouldBeFinalFailed() {
        FailRecord record = TestDataBuilder.createFailRecord(TASK_ID, "key_3", 3);
        record.setRetryCount(3);

        when(failRecordRepository.save(any(FailRecord.class)).thenAnswer(invocation -> invocation.getArgument(0)));

        boolean result = failService.updateRetryResult(record, false);

        assertFalse(result);
        assertEquals(FailStatus.FINAL_FAILED, record.getStatus());
    }

    @Test
    @DisplayName("获取待重试记录 - 返回正确数量")
    void getPendingRetryRecords_ShouldReturnPending() {
        List<FailRecord> expectedRecords = TestDataBuilder.createMultipleFailRecords(TASK_ID, 5, 3);
        when(failRecordRepository.findPendingRetryRecords(any(LocalDateTime.class))).thenReturn(expectedRecords);

        List<FailRecord> result = failService.getPendingRetryRecords();

        assertEquals(5, result.size());
    }

    @Test
    @DisplayName("获取任务失败记录 - 返回该任务所有失败")
    void getFailsByTaskId_ShouldReturnTaskFails() {
        List<FailRecord> expectedRecords = TestDataBuilder.createMultipleFailRecords(TASK_ID, 10, 3);
        when(failRecordRepository.findByTaskId(TASK_ID)).thenReturn(expectedRecords);

        List<FailRecord> result = failService.getFailsByTaskId(TASK_ID);

        assertEquals(10, result.size());
        assertTrue(result.stream().allMatch(r -> TASK_ID.equals(r.getTaskId())));
    }

    @Test
    @DisplayName("统计待重试数量 - 正确计数")
    void countPendingRetryByTaskId_ShouldCountCorrectly() {
        when(failRecordRepository.countByTaskIdAndStatus(TASK_ID, FailStatus.PENDING_RETRY)).thenReturn(3L);

        long count = failService.countPendingRetryByTaskId(TASK_ID);

        assertEquals(3L, count);
    }

    @Test
    @DisplayName("统计最终失败数量 - 正确计数")
    void countFinalFailedByTaskId_ShouldCountCorrectly() {
        when(failRecordRepository.countByTaskIdAndStatus(TASK_ID, FailStatus.FINAL_FAILED)).thenReturn(2L);

        long count = failService.countFinalFailedByTaskId(TASK_ID);

        assertEquals(2L, count);
    }

    @Test
    @DisplayName("重置所有待重试 - 重置重试计数")
    void resetAllPendingFails_ShouldResetRetryCount() {
        List<FailRecord> pendingRecords = TestDataBuilder.createMultipleFailRecords(TASK_ID, 3, 3);
        for (FailRecord record : pendingRecords) {
            record.setRetryCount(2);
        }
        when(failRecordRepository.findByTaskIdAndStatus(TASK_ID, FailStatus.PENDING_RETRY)).thenReturn(pendingRecords);

        failService.resetAllPendingFails(TASK_ID);

        for (FailRecord record : pendingRecords) {
            assertEquals(0, record.getRetryCount());
            assertEquals(FailStatus.PENDING_RETRY, record.getStatus());
        }
        verify(failRecordRepository, times(3)).save(any(FailRecord.class));
    }
}
