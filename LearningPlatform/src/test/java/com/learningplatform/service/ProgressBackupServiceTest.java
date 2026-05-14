
package com.learningplatform.service;

import com.learningplatform.builder.TestDataBuilder;
import com.learningplatform.entity.Progress;
import com.learningplatform.entity.ProgressBackup;
import com.learningplatform.exception.BusinessException;
import com.learningplatform.repository.ProgressBackupRepository;
import com.learningplatform.repository.ProgressRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("ProgressBackupService 进度备份服务测试")
class ProgressBackupServiceTest {

    @Mock
    private ProgressBackupRepository progressBackupRepository;

    @Mock
    private ProgressRepository progressRepository;

    @InjectMocks
    private ProgressBackupService progressBackupService;

    private Progress testProgress;
    private ProgressBackup testBackup;

    @BeforeEach
    void setUp() {
        testProgress = TestDataBuilder.createDefaultProgress();
        testBackup = TestDataBuilder.createDefaultBackup();
    }

    @Nested
    @DisplayName("备份创建测试")
    class BackupCreationTests {

        @Test
        @DisplayName("应该成功创建进度备份")
        void shouldCreateBackupSuccessfully() {
            when(progressRepository.findById(TestDataBuilder.TEST_PROGRESS_ID))
                    .thenReturn(Optional.of(testProgress));
            when(progressBackupRepository.save(any(ProgressBackup.class)))
                    .thenReturn(testBackup);

            ProgressBackup result = progressBackupService.createBackup(
                    TestDataBuilder.TEST_PROGRESS_ID, 
                    "scheduled"
            );

            assertNotNull(result);
            assertEquals(TestDataBuilder.TEST_BACKUP_ID, result.getBackupId());
            assertEquals(TestDataBuilder.TEST_PROGRESS_ID, result.getProgressId());
            verify(progressBackupRepository, times(1)).save(any(ProgressBackup.class));
        }

        @Test
        @DisplayName("当进度不存在时应该抛出异常")
        void shouldThrowExceptionWhenProgressNotFound() {
            when(progressRepository.findById("nonexistent_id"))
                    .thenReturn(Optional.empty());

            assertThrows(BusinessException.class, () ->
                progressBackupService.createBackup("nonexistent_id", "scheduled")
            );
        }

        @Test
        @DisplayName("应该正确设置备份原因")
        void shouldSetBackupReasonCorrectly() {
            when(progressRepository.findById(TestDataBuilder.TEST_PROGRESS_ID))
                    .thenReturn(Optional.of(testProgress));
            when(progressBackupRepository.save(any(ProgressBackup.class)))
                    .thenAnswer(invocation -> {
                        ProgressBackup backup = invocation.getArgument(0);
                        backup.setBackupId(TestDataBuilder.TEST_BACKUP_ID);
                        return backup;
                    });

            ProgressBackup result = progressBackupService.createBackup(
                    TestDataBuilder.TEST_PROGRESS_ID, 
                    "manual"
            );

            assertEquals("manual", result.getBackupReason());
        }

        @Test
        @DisplayName("备份应该包含完整的进度数据")
        void shouldContainCompleteProgressData() {
            testProgress.setProgressPercent(60);
            testProgress.setChaptersCompleted(3);
            testProgress.setTotalChapters(5);
            testProgress.setLearningTime(300L);

            when(progressRepository.findById(TestDataBuilder.TEST_PROGRESS_ID))
                    .thenReturn(Optional.of(testProgress));
            when(progressBackupRepository.save(any(ProgressBackup.class)))
                    .thenAnswer(invocation -> invocation.getArgument(0));

            ProgressBackup result = progressBackupService.createBackup(
                    TestDataBuilder.TEST_PROGRESS_ID, 
                    "auto"
            );

            assertEquals(testProgress.getProgressPercent(), result.getProgressPercent());
            assertEquals(testProgress.getChaptersCompleted(), result.getChaptersCompleted());
            assertEquals(testProgress.getTotalChapters(), result.getTotalChapters());
            assertEquals(testProgress.getLearningTime(), result.getLearningTime());
            assertEquals(testProgress.getProgressStatus(), result.getProgressStatus());
        }
    }

    @Nested
    @DisplayName("备份级别与频率测试")
    class BackupLevelAndFrequencyTests {

        @Test
        @DisplayName("低频学习应该使用低级别备份")
        void shouldUseLowLevelForLowActivity() {
            progressBackupService.resetActivityCounter(TestDataBuilder.TEST_PROGRESS_ID);
            progressBackupService.setActivityLevel(TestDataBuilder.TEST_PROGRESS_ID, 2);

            String level = progressBackupService.determineBackupLevel(TestDataBuilder.TEST_PROGRESS_ID);
            
            assertEquals("low", level);
        }

        @Test
        @DisplayName("中频学习应该使用中级备份")
        void shouldUseMediumLevelForMediumActivity() {
            progressBackupService.resetActivityCounter(TestDataBuilder.TEST_PROGRESS_ID);
            progressBackupService.setActivityLevel(TestDataBuilder.TEST_PROGRESS_ID, 7);

            String level = progressBackupService.determineBackupLevel(TestDataBuilder.TEST_PROGRESS_ID);
            
            assertEquals("medium", level);
        }

        @Test
        @DisplayName("高频学习应该使用高级备份")
        void shouldUseHighLevelForHighActivity() {
            progressBackupService.resetActivityCounter(TestDataBuilder.TEST_PROGRESS_ID);
            progressBackupService.setActivityLevel(TestDataBuilder.TEST_PROGRESS_ID, 15);

            String level = progressBackupService.determineBackupLevel(TestDataBuilder.TEST_PROGRESS_ID);
            
            assertEquals("high", level);
        }

        @Test
        @DisplayName("高级备份应该有更高的备份频率")
        void shouldHaveHigherFrequencyForHighLevel() {
            progressBackupService.setActivityLevel("progress_high", 15);
            progressBackupService.setActivityLevel("progress_medium", 7);
            progressBackupService.setActivityLevel("progress_low", 2);

            int highFreq = progressBackupService.getBackupFrequency("progress_high");
            int mediumFreq = progressBackupService.getBackupFrequency("progress_medium");
            int lowFreq = progressBackupService.getBackupFrequency("progress_low");

            assertTrue(highFreq > mediumFreq);
            assertTrue(mediumFreq >= lowFreq);
            assertEquals(5, highFreq);
            assertEquals(3, mediumFreq);
            assertEquals(1, lowFreq);
        }

        @Test
        @DisplayName("每次备份后应该增加活跃度")
        void shouldIncrementActivityAfterBackup() {
            when(progressRepository.findById(TestDataBuilder.TEST_PROGRESS_ID))
                    .thenReturn(Optional.of(testProgress));
            when(progressBackupRepository.save(any(ProgressBackup.class)))
                    .thenReturn(testBackup);

            progressBackupService.resetActivityCounter(TestDataBuilder.TEST_PROGRESS_ID);
            
            assertEquals(0, progressBackupService.getActivityLevel(TestDataBuilder.TEST_PROGRESS_ID));

            progressBackupService.createBackup(TestDataBuilder.TEST_PROGRESS_ID, "test");
            
            assertEquals(1, progressBackupService.getActivityLevel(TestDataBuilder.TEST_PROGRESS_ID));
        }

        @Test
        @DisplayName("应该正确判断是否需要备份")
        void shouldDetermineIfBackupNeeded() {
            ProgressBackup recentBackup = TestDataBuilder.createDefaultBackup();
            recentBackup.setBackupTime(LocalDateTime.now().minusSeconds(30));

            when(progressBackupRepository.findFirstByProgressIdOrderByBackupTimeDesc("progress_recent"))
                    .thenReturn(Optional.of(recentBackup));
            when(progressBackupRepository.findFirstByProgressIdOrderByBackupTimeDesc("progress_no_backup"))
                    .thenReturn(Optional.empty());

            assertTrue(progressBackupService.shouldBackup("progress_no_backup"));
            assertFalse(progressBackupService.shouldBackup("progress_recent"));
        }
    }

    @Nested
    @DisplayName("备份完整性校验测试")
    class BackupVerificationTests {

        @Test
        @DisplayName("当进度数据匹配时应该通过校验")
        void shouldPassVerificationWhenDataMatches() {
            Progress progress = TestDataBuilder.createDefaultProgress();
            ProgressBackup backup = TestDataBuilder.createDefaultBackup();
            backup.setProgressId(TestDataBuilder.TEST_PROGRESS_ID);
            backup.setCourseId(progress.getCourseId());
            backup.setStudentId(progress.getStudentId());
            backup.setProgressPercent(progress.getProgressPercent());
            backup.setChaptersCompleted(progress.getChaptersCompleted());
            backup.setTotalChapters(progress.getTotalChapters());

            when(progressBackupRepository.findById(TestDataBuilder.TEST_BACKUP_ID))
                    .thenReturn(Optional.of(backup));
            when(progressRepository.findById(TestDataBuilder.TEST_PROGRESS_ID))
                    .thenReturn(Optional.of(progress));
            when(progressBackupRepository.save(any(ProgressBackup.class)))
                    .thenAnswer(invocation -> invocation.getArgument(0));

            boolean result = progressBackupService.verifyBackup(TestDataBuilder.TEST_BACKUP_ID);

            assertTrue(result);
            verify(progressBackupRepository).save(any(ProgressBackup.class));
        }

        @Test
        @DisplayName("当进度数据不匹配时应该失败校验")
        void shouldFailVerificationWhenDataMismatch() {
            Progress progress = TestDataBuilder.createDefaultProgress();
            progress.setProgressPercent(50);

            ProgressBackup backup = TestDataBuilder.createDefaultBackup();
            backup.setProgressId(TestDataBuilder.TEST_PROGRESS_ID);
            backup.setCourseId(progress.getCourseId());
            backup.setStudentId(progress.getStudentId());
            backup.setProgressPercent(60);
            backup.setChaptersCompleted(progress.getChaptersCompleted());
            backup.setTotalChapters(progress.getTotalChapters());

            when(progressBackupRepository.findById(TestDataBuilder.TEST_BACKUP_ID))
                    .thenReturn(Optional.of(backup));
            when(progressRepository.findById(TestDataBuilder.TEST_PROGRESS_ID))
                    .thenReturn(Optional.of(progress));
            when(progressBackupRepository.save(any(ProgressBackup.class)))
                    .thenAnswer(invocation -> invocation.getArgument(0));

            boolean result = progressBackupService.verifyBackup(TestDataBuilder.TEST_BACKUP_ID);

            assertFalse(result);
        }

        @Test
        @DisplayName("当进度不存在时应该失败校验")
        void shouldFailVerificationWhenProgressNotFound() {
            ProgressBackup backup = TestDataBuilder.createDefaultBackup();

            when(progressBackupRepository.findById(TestDataBuilder.TEST_BACKUP_ID))
                    .thenReturn(Optional.of(backup));
            when(progressRepository.findById(TestDataBuilder.TEST_PROGRESS_ID))
                    .thenReturn(Optional.empty());
            when(progressBackupRepository.save(any(ProgressBackup.class)))
                    .thenAnswer(invocation -> invocation.getArgument(0));

            boolean result = progressBackupService.verifyBackup(TestDataBuilder.TEST_BACKUP_ID);

            assertFalse(result);
        }

        @Test
        @DisplayName("备份不存在时应该抛出异常")
        void shouldThrowExceptionWhenBackupNotFound() {
            when(progressBackupRepository.findById("nonexistent_backup"))
                    .thenReturn(Optional.empty());

            assertThrows(BusinessException.class, () ->
                progressBackupService.verifyBackup("nonexistent_backup")
            );
        }
    }

    @Nested
    @DisplayName("进度恢复测试")
    class ProgressRecoveryTests {

        @Test
        @DisplayName("应该成功从备份恢复进度")
        void shouldRestoreProgressFromBackup() {
            ProgressBackup backup = TestDataBuilder.createDefaultBackup();
            backup.setProgressPercent(75);
            backup.setChaptersCompleted(3);
            backup.setTotalChapters(4);
            backup.setLearningTime(400L);

            Progress existingProgress = TestDataBuilder.createDefaultProgress();

            when(progressBackupRepository.findById(TestDataBuilder.TEST_BACKUP_ID))
                    .thenReturn(Optional.of(backup));
            when(progressRepository.findById(TestDataBuilder.TEST_PROGRESS_ID))
                    .thenReturn(Optional.of(existingProgress));
            when(progressRepository.save(any(Progress.class)))
                    .thenAnswer(invocation -> invocation.getArgument(0));

            Progress restored = progressBackupService.restoreFromBackup(TestDataBuilder.TEST_BACKUP_ID);

            assertNotNull(restored);
            assertEquals(backup.getProgressPercent(), restored.getProgressPercent());
            assertEquals(backup.getChaptersCompleted(), restored.getChaptersCompleted());
            assertEquals(backup.getTotalChapters(), restored.getTotalChapters());
            assertEquals(backup.getLearningTime(), restored.getLearningTime());
            assertEquals(backup.getProgressStatus(), restored.getProgressStatus());
        }

        @Test
        @DisplayName("当进度不存在时应该创建新进度")
        void shouldCreateNewProgressWhenNotExists() {
            ProgressBackup backup = TestDataBuilder.createDefaultBackup();

            when(progressBackupRepository.findById(TestDataBuilder.TEST_BACKUP_ID))
                    .thenReturn(Optional.of(backup));
            when(progressRepository.findById(TestDataBuilder.TEST_PROGRESS_ID))
                    .thenReturn(Optional.empty());
            when(progressRepository.save(any(Progress.class)))
                    .thenAnswer(invocation -> invocation.getArgument(0));

            Progress restored = progressBackupService.restoreFromBackup(TestDataBuilder.TEST_BACKUP_ID);

            assertNotNull(restored);
            assertEquals(TestDataBuilder.TEST_PROGRESS_ID, restored.getProgressId());
        }

        @Test
        @DisplayName("恢复后数据应该与备份一致")
        void restoredDataShouldMatchBackup() {
            ProgressBackup backup = TestDataBuilder.createDefaultBackup();
            backup.setCourseId("course_restore_test");
            backup.setStudentId("student_restore_test");

            when(progressBackupRepository.findById(TestDataBuilder.TEST_BACKUP_ID))
                    .thenReturn(Optional.of(backup));
            when(progressRepository.findById(TestDataBuilder.TEST_PROGRESS_ID))
                    .thenReturn(Optional.empty());
            when(progressRepository.save(any(Progress.class)))
                    .thenAnswer(invocation -> invocation.getArgument(0));

            Progress restored = progressBackupService.restoreFromBackup(TestDataBuilder.TEST_BACKUP_ID);

            assertEquals(backup.getCourseId(), restored.getCourseId());
            assertEquals(backup.getStudentId(), restored.getStudentId());
        }
    }

    @Nested
    @DisplayName("批量备份测试")
    class BatchBackupTests {

        @Test
        @DisplayName("应该成功执行批量备份")
        void shouldPerformBatchBackup() {
            List<String> progressIds = Arrays.asList("p1", "p2", "p3");

            when(progressBackupRepository.findFirstByProgressIdOrderByBackupTimeDesc(anyString()))
                    .thenReturn(Optional.empty());
            when(progressRepository.findById(anyString()))
                    .thenReturn(Optional.of(testProgress));
            when(progressBackupRepository.save(any(ProgressBackup.class)))
                    .thenReturn(testBackup);

            List<ProgressBackup> backups = progressBackupService.batchBackup(progressIds, "scheduled");

            assertEquals(3, backups.size());
            verify(progressBackupRepository, times(3)).save(any(ProgressBackup.class));
        }

        @Test
        @DisplayName("应该跳过不需要备份的进度")
        void shouldSkipProgressThatDoesNotNeedBackup() {
            ProgressBackup recentBackup = TestDataBuilder.createDefaultBackup();
            recentBackup.setBackupTime(LocalDateTime.now().minusSeconds(10));

            when(progressBackupRepository.findFirstByProgressIdOrderByBackupTimeDesc("needs_backup"))
                    .thenReturn(Optional.empty());
            when(progressBackupRepository.findFirstByProgressIdOrderByBackupTimeDesc("no_backup_needed"))
                    .thenReturn(Optional.of(recentBackup));

            when(progressRepository.findById("needs_backup"))
                    .thenReturn(Optional.of(testProgress));
            when(progressBackupRepository.save(any(ProgressBackup.class)))
                    .thenReturn(testBackup);

            List<String> progressIds = Arrays.asList("needs_backup", "no_backup_needed");
            List<ProgressBackup> backups = progressBackupService.batchBackup(progressIds, "scheduled");

            assertEquals(1, backups.size());
        }
    }

    @Nested
    @DisplayName("备份查询测试")
    class BackupQueryTests {

        @Test
        @DisplayName("应该正确获取最新备份")
        void shouldGetLatestBackup() {
            when(progressBackupRepository.findFirstByProgressIdOrderByBackupTimeDesc(TestDataBuilder.TEST_PROGRESS_ID))
                    .thenReturn(Optional.of(testBackup));

            Optional<ProgressBackup> latest = progressBackupService.getLatestBackup(TestDataBuilder.TEST_PROGRESS_ID);

            assertTrue(latest.isPresent());
            assertEquals(TestDataBuilder.TEST_BACKUP_ID, latest.get().getBackupId());
        }

        @Test
        @DisplayName("应该正确获取备份列表")
        void shouldGetBackupList() {
            List<ProgressBackup> backups = TestDataBuilder.createMultipleBackups(TestDataBuilder.TEST_PROGRESS_ID, 5);
            when(progressBackupRepository.findByProgressIdOrderByBackupTimeDesc(TestDataBuilder.TEST_PROGRESS_ID))
                    .thenReturn(backups);

            List<ProgressBackup> result = progressBackupService.getBackupsByProgress(TestDataBuilder.TEST_PROGRESS_ID);

            assertEquals(5, result.size());
        }

        @Test
        @DisplayName("应该正确获取备份计数")
        void shouldGetBackupCount() {
            when(progressBackupRepository.countByProgressId(TestDataBuilder.TEST_PROGRESS_ID))
                    .thenReturn(10L);

            long count = progressBackupService.getBackupCount(TestDataBuilder.TEST_PROGRESS_ID);

            assertEquals(10L, count);
        }
    }
}
