package com.datamigrate.service;

import com.datamigrate.builder.TestDataBuilder;
import com.datamigrate.dto.ProgressResponse;
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
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("进度监控模块测试")
class ProgressServiceTest {

    @Mock
    private MigrateProgressRepository progressRepository;

    @InjectMocks
    private ProgressService progressService;

    private static final String TASK_ID = "test_task_001";
    private static final long TOTAL_RECORDS = 1000L;

    @Test
    @DisplayName("创建进度记录 - 初始化进度数据正确")
    void createProgress_ShouldInitializeCorrectly() {
        MigrateProgress expectedProgress = TestDataBuilder.createProgress(TASK_ID, TOTAL_RECORDS);
        when(progressRepository.save(any(MigrateProgress.class))).thenReturn(expectedProgress);

        MigrateProgress result = progressService.createProgress(TASK_ID, TOTAL_RECORDS);

        assertNotNull(result);
        assertEquals(TASK_ID, result.getTaskId());
        assertEquals(TOTAL_RECORDS, result.getTotalRecords());
        assertEquals(0L, result.getMigratedRecords());
        assertEquals(0L, result.getSuccessRecords());
        assertEquals(0L, result.getFailRecords());
        assertEquals(0, result.getProgressRate());
        assertEquals(0, result.getCurrentBatch());
        verify(progressRepository).save(any(MigrateProgress.class));
    }

    @Test
    @DisplayName("更新进度 - 批量更新后数值正确")
    void updateProgress_ShouldUpdateValues() {
        MigrateProgress progress = TestDataBuilder.createProgress(TASK_ID, TOTAL_RECORDS);
        when(progressRepository.findByTaskId(TASK_ID)).thenReturn(Optional.of(progress));

        progressService.updateProgress(TASK_ID, 500L, 480L, 20L);

        assertEquals(500L, progress.getMigratedRecords());
        assertEquals(480L, progress.getSuccessRecords());
        assertEquals(20L, progress.getFailRecords());
        verify(progressRepository).save(progress);
    }

    @Test
    @DisplayName("增量更新进度 - 单条成功记录")
    void incrementProgress_WithSuccess_ShouldIncrementSuccess() {
        MigrateProgress progress = TestDataBuilder.createProgress(TASK_ID, TOTAL_RECORDS);
        progress.setMigratedRecords(100L);
        progress.setSuccessRecords(95L);
        progress.setFailRecords(5L);
        when(progressRepository.findByTaskId(TASK_ID)).thenReturn(Optional.of(progress));

        progressService.incrementProgress(TASK_ID, true);

        assertEquals(101L, progress.getMigratedRecords());
        assertEquals(96L, progress.getSuccessRecords());
        assertEquals(5L, progress.getFailRecords());
        verify(progressRepository).save(progress);
    }

    @Test
    @DisplayName("增量更新进度 - 单条失败记录")
    void incrementProgress_WithFailure_ShouldIncrementFail() {
        MigrateProgress progress = TestDataBuilder.createProgress(TASK_ID, TOTAL_RECORDS);
        progress.setMigratedRecords(100L);
        progress.setSuccessRecords(95L);
        progress.setFailRecords(5L);
        when(progressRepository.findByTaskId(TASK_ID)).thenReturn(Optional.of(progress));

        progressService.incrementProgress(TASK_ID, false);

        assertEquals(101L, progress.getMigratedRecords());
        assertEquals(95L, progress.getSuccessRecords());
        assertEquals(6L, progress.getFailRecords());
        verify(progressRepository).save(progress);
    }

    @Test
    @DisplayName("批量进度更新 - 批次处理后进度正确")
    void incrementBatch_ShouldUpdateBatchProgress() {
        MigrateProgress progress = TestDataBuilder.createProgress(TASK_ID, TOTAL_RECORDS);
        progress.setMigratedRecords(200L);
        progress.setSuccessRecords(190L);
        progress.setFailRecords(10L);
        progress.setCurrentBatch(2);
        when(progressRepository.findByTaskId(TASK_ID)).thenReturn(Optional.of(progress));

        progressService.incrementBatch(TASK_ID, 100L, 98L, 2L);

        assertEquals(300L, progress.getMigratedRecords());
        assertEquals(288L, progress.getSuccessRecords());
        assertEquals(12L, progress.getFailRecords());
        assertEquals(3, progress.getCurrentBatch());
        verify(progressRepository).save(progress);
    }

    @Test
    @DisplayName("进度百分比计算 - 50%完成率")
    void progressRate_CalculationAt50Percent() {
        MigrateProgress progress = TestDataBuilder.createProgress(TASK_ID, 1000L);
        when(progressRepository.findByTaskId(TASK_ID)).thenReturn(Optional.of(progress));

        progressService.updateProgress(TASK_ID, 500L, 480L, 20L);

        assertEquals(50, progress.getProgressRate());
    }

    @Test
    @DisplayName("进度百分比计算 - 100%完成率")
    void progressRate_CalculationAt100Percent() {
        MigrateProgress progress = TestDataBuilder.createProgress(TASK_ID, 1000L);
        when(progressRepository.findByTaskId(TASK_ID)).thenReturn(Optional.of(progress));

        progressService.updateProgress(TASK_ID, 1000L, 990L, 10L);

        assertEquals(100, progress.getProgressRate());
    }

    @Test
    @DisplayName("进度百分比计算 - 边界情况0%")
    void progressRate_CalculationAt0Percent() {
        MigrateProgress progress = TestDataBuilder.createProgress(TASK_ID, 1000L);
        when(progressRepository.findByTaskId(TASK_ID)).thenReturn(Optional.of(progress));

        progressService.updateProgress(TASK_ID, 0L, 0L, 0L);

        assertEquals(0, progress.getProgressRate());
    }

    @Test
    @DisplayName("更新断点位置 - 可续传状态")
    void updatePosition_ShouldSetResumableTrue() {
        MigrateProgress progress = TestDataBuilder.createProgress(TASK_ID, TOTAL_RECORDS);
        progress.setIsResumable(false);
        when(progressRepository.findByTaskId(TASK_ID)).thenReturn(Optional.of(progress));

        progressService.updatePosition(TASK_ID, 500L, "key_500", true);

        assertEquals(500L, progress.getCurrentPosition());
        assertEquals("key_500", progress.getLastProcessedKey());
        assertTrue(progress.getIsResumable());
        verify(progressRepository).save(progress);
    }

    @Test
    @DisplayName("获取进度响应 - DTO转换正确")
    void getProgressResponse_ShouldConvertToDTO() {
        MigrateProgress progress = TestDataBuilder.createProgressAtPoint(
            TASK_ID, 1000L, 500L, 480L, 20L, 5, "key_500"
        );
        when(progressRepository.findByTaskId(TASK_ID)).thenReturn(Optional.of(progress));

        ProgressResponse response = progressService.getProgressResponse(TASK_ID);

        assertNotNull(response);
        assertNotNull(response.getProgress());
        assertEquals(1000L, response.getProgress().getTotalRecords());
        assertEquals(500L, response.getProgress().getMigratedRecords());
        assertEquals(480L, response.getProgress().getSuccessRecords());
        assertEquals(20L, response.getProgress().getFailRecords());
        assertEquals(50, response.getProgress().getProgressRate());
        assertEquals(5, response.getProgress().getCurrentBatch());
    }

    @Test
    @DisplayName("获取进度响应 - 无进度时返回空对象")
    void getProgressResponse_WhenNoProgress_ShouldReturnEmpty() {
        when(progressRepository.findByTaskId(TASK_ID)).thenReturn(Optional.empty());

        ProgressResponse response = progressService.getProgressResponse(TASK_ID);

        assertNotNull(response);
        assertNotNull(response.getProgress());
        assertNull(response.getProgress().getTotalRecords());
    }

    @Test
    @DisplayName("重置进度 - 所有数值归零")
    void resetProgress_ShouldResetAllValues() {
        MigrateProgress progress = TestDataBuilder.createProgressAtPoint(
            TASK_ID, 1000L, 500L, 480L, 20L, 5, "key_500"
        );
        when(progressRepository.findByTaskId(TASK_ID)).thenReturn(Optional.of(progress));

        progressService.resetProgress(TASK_ID);

        assertEquals(0L, progress.getMigratedRecords());
        assertEquals(0L, progress.getSuccessRecords());
        assertEquals(0L, progress.getFailRecords());
        assertEquals(0, progress.getProgressRate());
        assertEquals(0, progress.getCurrentBatch());
        assertEquals(0L, progress.getCurrentPosition());
        assertNull(progress.getLastProcessedKey());
        verify(progressRepository).save(progress);
    }

    @Test
    @DisplayName("进度查询实时性 - 多次查询返回最新数据")
    void getProgress_MultipleQueries_ShouldReturnLatest() {
        MigrateProgress progress = TestDataBuilder.createProgress(TASK_ID, 100L);
        when(progressRepository.findByTaskId(TASK_ID)).thenReturn(Optional.of(progress));

        progressService.incrementProgress(TASK_ID, true);
        progressService.incrementProgress(TASK_ID, true);
        progressService.incrementProgress(TASK_ID, false);

        assertEquals(3L, progress.getMigratedRecords());
        assertEquals(2L, progress.getSuccessRecords());
        assertEquals(1L, progress.getFailRecords());
    }

    @Test
    @DisplayName("异常中断时进度保存 - 部分完成状态")
    void progressSave_OnPartialCompletion_ShouldPreserveState() {
        MigrateProgress progress = TestDataBuilder.createProgress(TASK_ID, 1000L);
        when(progressRepository.findByTaskId(TASK_ID)).thenReturn(Optional.of(progress));

        progressService.updateProgress(TASK_ID, 350L, 340L, 10L);
        progressService.updatePosition(TASK_ID, 350L, "id_350", true);

        assertTrue(progress.getIsResumable());
        assertEquals("id_350", progress.getLastProcessedKey());
        assertEquals(350L, progress.getCurrentPosition());
        verify(progressRepository, times(2)).save(progress);
    }

    @Test
    @DisplayName("空任务ID - 不抛出异常")
    void updateProgress_WithNullTask_ShouldNotThrow() {
        when(progressRepository.findByTaskId(null)).thenReturn(Optional.empty());

        assertDoesNotThrow(() -> {
            progressService.updateProgress(null, 100L, 90L, 10L);
        });

        verify(progressRepository, never()).save(any());
    }

    @Test
    @DisplayName("总记录数为0 - 进度率保持0不除零")
    void progressRate_WithZeroTotal_ShouldNotDivideByZero() {
        MigrateProgress progress = TestDataBuilder.createProgress(TASK_ID, 0L);
        when(progressRepository.findByTaskId(TASK_ID)).thenReturn(Optional.of(progress));

        assertDoesNotThrow(() -> {
            progressService.updateProgress(TASK_ID, 0L, 0L, 0L);
        });

        assertEquals(0, progress.getProgressRate());
    }
}
