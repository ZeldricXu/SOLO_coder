package com.datamigrate.service;

import com.datamigrate.builder.TestDataBuilder;
import com.datamigrate.entity.MigrateProgress;
import com.datamigrate.repository.MigrateProgressRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("断点续传管理器测试")
class ResumeManagerTest {

    @Mock
    private MigrateProgressRepository progressRepository;

    @Mock
    private ProgressService progressService;

    @InjectMocks
    private ResumeManager resumeManager;

    private static final String TASK_ID = "test_resume_001";
    private static final long TOTAL_RECORDS = 10000L;

    @BeforeEach
    void setUp() {
    }

    @Test
    @DisplayName("获取断点状态 - 无可续传点时返回空状态")
    void getResumeState_WithoutProgress_ShouldReturnEmpty() {
        when(progressService.getProgress(TASK_ID)).thenReturn(Optional.empty());

        ResumeManager.ResumeState state = resumeManager.getResumeState(TASK_ID);

        assertFalse(state.isResumable());
        assertEquals(0, state.getResumePosition());
        assertNull(state.getLastProcessedKey());
        assertEquals(0, state.getMigratedRecords());
    }

    @Test
    @DisplayName("获取断点状态 - 有断点时返回完整状态")
    void getResumeState_WithValidResumePoint_ShouldReturnCompleteState() {
        MigrateProgress progress = TestDataBuilder.createProgressAtPoint(
            TASK_ID, TOTAL_RECORDS, 5000L, 4980L, 20L, 50, "key_5000"
        );
        when(progressService.getProgress(TASK_ID)).thenReturn(Optional.of(progress));

        ResumeManager.ResumeState state = resumeManager.getResumeState(TASK_ID);

        assertTrue(state.isResumable());
        assertEquals(5000L, state.getResumePosition());
        assertEquals("key_5000", state.getLastProcessedKey());
        assertEquals(5000L, state.getMigratedRecords());
        assertEquals(4980L, state.getSuccessRecords());
        assertEquals(20L, state.getFailRecords());
    }

    @Test
    @DisplayName("获取断点状态 - 位置为0时不可续传")
    void getResumeState_WithZeroPosition_ShouldNotBeResumable() {
        MigrateProgress progress = TestDataBuilder.createProgressAtPoint(
            TASK_ID, TOTAL_RECORDS, 0L, 0L, 0L, 0, null
        );
        when(progressService.getProgress(TASK_ID)).thenReturn(Optional.of(progress));

        ResumeManager.ResumeState state = resumeManager.getResumeState(TASK_ID);

        assertFalse(state.isResumable());
    }

    @Test
    @DisplayName("获取断点状态 - isResumable为false时不可续传")
    void getResumeState_WithResumableFalse_ShouldNotBeResumable() {
        MigrateProgress progress = TestDataBuilder.createProgress(TASK_ID, TOTAL_RECORDS);
        progress.setCurrentPosition(5000L);
        progress.setIsResumable(false);
        when(progressService.getProgress(TASK_ID)).thenReturn(Optional.of(progress));

        ResumeManager.ResumeState state = resumeManager.getResumeState(TASK_ID);

        assertFalse(state.isResumable());
    }

    @Test
    @DisplayName("标记已处理记录 - 首次标记返回true")
    void markProcessed_FirstTime_ShouldReturnTrue() {
        boolean result = resumeManager.markProcessed(TASK_ID, "record_1");

        assertTrue(result);
        assertTrue(resumeManager.isProcessed(TASK_ID, "record_1"));
    }

    @Test
    @DisplayName("标记已处理记录 - 重复标记返回false")
    void markProcessed_Duplicate_ShouldReturnFalse() {
        resumeManager.markProcessed(TASK_ID, "record_2");
        boolean result = resumeManager.markProcessed(TASK_ID, "record_2");

        assertFalse(result);
    }

    @Test
    @DisplayName("设置断点续传点 - 正确保存进度")
    void setResumePoint_ShouldUpdateProgressService() {
        doNothing().when(progressService).updatePosition(anyString(), anyLong(), anyString(), eq(true));

        resumeManager.setResumePoint(TASK_ID, 3000L, "key_3000");

        verify(progressService).updatePosition(TASK_ID, 3000L, "key_3000", true);
    }

    @Test
    @DisplayName("验证断点位置 - 位置和key都匹配时返回true")
    void validateResumePoint_WithMatchingData_ShouldReturnTrue() {
        MigrateProgress progress = TestDataBuilder.createProgressAtPoint(
            TASK_ID, TOTAL_RECORDS, 7000L, 6980L, 20L, 70, "key_7000"
        );
        when(progressService.getProgress(TASK_ID)).thenReturn(Optional.of(progress));

        boolean valid = resumeManager.validateResumePoint(TASK_ID, 7000L, "key_7000");

        assertTrue(valid);
    }

    @Test
    @DisplayName("验证断点位置 - 位置不匹配时返回false")
    void validateResumePoint_WithWrongPosition_ShouldReturnFalse() {
        MigrateProgress progress = TestDataBuilder.createProgressAtPoint(
            TASK_ID, TOTAL_RECORDS, 7000L, 6980L, 20L, 70, "key_7000"
        );
        when(progressService.getProgress(TASK_ID)).thenReturn(Optional.of(progress));

        boolean valid = resumeManager.validateResumePoint(TASK_ID, 8000L, "key_7000");

        assertFalse(valid);
    }

    @Test
    @DisplayName("验证断点位置 - key不匹配时返回false")
    void validateResumePoint_WithWrongKey_ShouldReturnFalse() {
        MigrateProgress progress = TestDataBuilder.createProgressAtPoint(
            TASK_ID, TOTAL_RECORDS, 7000L, 6980L, 20L, 70, "key_7000"
        );
        when(progressService.getProgress(TASK_ID)).thenReturn(Optional.of(progress));

        boolean valid = resumeManager.validateResumePoint(TASK_ID, 7000L, "key_7001");

        assertFalse(valid);
    }

    @Test
    @DisplayName("清空任务状态 - 移除所有已处理记录")
    void clearTaskState_ShouldRemoveAllProcessedKeys() {
        resumeManager.markProcessed(TASK_ID, "key_1");
        resumeManager.markProcessed(TASK_ID, "key_2");
        resumeManager.markProcessed(TASK_ID, "key_3");

        assertEquals(3, resumeManager.getProcessedCount(TASK_ID));

        resumeManager.clearTaskState(TASK_ID);

        assertEquals(0, resumeManager.getProcessedCount(TASK_ID));
    }

    @Test
    @DisplayName("清空任务状态 - 不影响其他任务")
    void clearTaskState_ShouldNotAffectOtherTasks() {
        String otherTaskId = "other_task_002";
        resumeManager.markProcessed(TASK_ID, "key_1");
        resumeManager.markProcessed(otherTaskId, "key_a");

        resumeManager.clearTaskState(TASK_ID);

        assertEquals(0, resumeManager.getProcessedCount(TASK_ID));
        assertEquals(1, resumeManager.getProcessedCount(otherTaskId));
    }

    @Test
    @DisplayName("迁移中断后续传 - 从正确位置继续")
    void resumeAfterInterruption_ShouldContinueFromBreakPoint() {
        MigrateProgress progress = TestDataBuilder.createProgressAtPoint(
            TASK_ID, TOTAL_RECORDS, 3500L, 3490L, 10L, 35, "key_3500"
        );
        when(progressService.getProgress(TASK_ID)).thenReturn(Optional.of(progress));

        ResumeManager.ResumeState state = resumeManager.getResumeState(TASK_ID);

        assertTrue(state.isResumable());
        assertEquals(3500L, state.getResumePosition());

        resumeManager.markProcessed(TASK_ID, "key_3501");
        resumeManager.markProcessed(TASK_ID, "key_3502");

        assertEquals(2, resumeManager.getProcessedCount(TASK_ID));
    }

    @Test
    @DisplayName("多次中断续传 - 累积断点位置正确")
    void multipleInterruptionsAndResumes_ShouldAccumulateCorrectly() {
        MigrateProgress progress1 = TestDataBuilder.createProgressAtPoint(
            TASK_ID, TOTAL_RECORDS, 2000L, 1990L, 10L, 20, "key_2000"
        );
        when(progressService.getProgress(TASK_ID)).thenReturn(Optional.of(progress1));

        ResumeManager.ResumeState state1 = resumeManager.getResumeState(TASK_ID);
        assertEquals(2000L, state1.getResumePosition());

        MigrateProgress progress2 = TestDataBuilder.createProgressAtPoint(
            TASK_ID, TOTAL_RECORDS, 5000L, 4980L, 20L, 50, "key_5000"
        );
        when(progressService.getProgress(TASK_ID)).thenReturn(Optional.of(progress2));

        ResumeManager.ResumeState state2 = resumeManager.getResumeState(TASK_ID);
        assertEquals(5000L, state2.getResumePosition());

        MigrateProgress progress3 = TestDataBuilder.createProgressAtPoint(
            TASK_ID, TOTAL_RECORDS, 8000L, 7980L, 20L, 80, "key_8000"
        );
        when(progressService.getProgress(TASK_ID)).thenReturn(Optional.of(progress3));

        ResumeManager.ResumeState state3 = resumeManager.getResumeState(TASK_ID);
        assertEquals(8000L, state3.getResumePosition());
    }

    @Test
    @DisplayName("批次进度注册 - 更新断点位置")
    void registerBatchProgress_ShouldUpdateResumePoint() {
        doNothing().when(progressService).updatePosition(anyString(), anyLong(), anyString(), eq(true));

        resumeManager.registerBatchProgress(TASK_ID, 0L, 1000L, "key_1000", 990L, 10L);

        verify(progressService).updatePosition(TASK_ID, 1000L, "key_1000", true);
    }

    @Test
    @DisplayName("批次进度注册 - 空批次不更新")
    void registerBatchProgress_WithEmptyBatch_ShouldNotUpdate() {
        resumeManager.registerBatchProgress(TASK_ID, 1000L, 1000L, "key_1000", 0L, 0L);

        verify(progressService, never()).updatePosition(anyString(), anyLong(), anyString(), anyBoolean());
    }
}
