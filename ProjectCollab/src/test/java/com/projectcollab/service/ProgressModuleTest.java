package com.projectcollab.service;

import com.projectcollab.builder.TestDataBuilder;
import com.projectcollab.dto.UpdateProgressRequest;
import com.projectcollab.dto.UpdateProgressResponse;
import com.projectcollab.entity.*;
import com.projectcollab.exception.ProjectCollabException;
import com.projectcollab.repository.*;
import com.projectcollab.service.progress.ProgressService;
import com.projectcollab.service.task.TaskService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
@DisplayName("进度模块单元测试")
class ProgressModuleTest {

    @Autowired
    private TaskService taskService;

    @Autowired
    private ProgressService progressService;

    @Autowired
    private TaskRepository taskRepository;

    @Autowired
    private ProjectRepository projectRepository;

    @Autowired
    private ProjectMemberRepository memberRepository;

    @Autowired
    private StageRepository stageRepository;

    @Autowired
    private ProgressRepository progressRepository;

    @Autowired
    private ReminderRepository reminderRepository;

    private Project testProject;
    private ProjectMember testMember;
    private Stage testStage;

    @BeforeEach
    void setUp() {
        testProject = TestDataBuilder.buildInProgressProject();
        projectRepository.save(testProject);

        testMember = TestDataBuilder.buildMember(testProject, "user_001");
        memberRepository.save(testMember);

        testStage = TestDataBuilder.buildDevelopmentStage(testProject);
        stageRepository.save(testStage);
    }

    @Nested
    @DisplayName("进度更新流程测试")
    class ProgressUpdateFlowTests {

        private Task testTask;

        @BeforeEach
        void setUpTask() {
            testTask = TestDataBuilder.buildAssignedTask(testProject, "development", "user_001");
            taskRepository.save(testTask);
        }

        @Test
        @DisplayName("测试进度更新为0%")
        void testUpdateProgress_ToZero() {
            UpdateProgressRequest request = TestDataBuilder.buildUpdateProgressRequest(testTask.getTaskId(), 0);
            UpdateProgressResponse response = taskService.updateProgress(request);

            assertEquals(0, response.getProjectProgress());

            Optional<Task> updatedTask = taskRepository.findById(testTask.getTaskId());
            assertTrue(updatedTask.isPresent());
            assertEquals(0, updatedTask.get().getTaskProgress());
            assertEquals("assigned", updatedTask.get().getTaskStatus());
        }

        @Test
        @DisplayName("测试进度更新为25%")
        void testUpdateProgress_To25() {
            UpdateProgressRequest request = TestDataBuilder.buildUpdateProgressRequest(testTask.getTaskId(), 25);
            UpdateProgressResponse response = taskService.updateProgress(request);

            assertEquals(25, response.getProjectProgress());

            Optional<Task> updatedTask = taskRepository.findById(testTask.getTaskId());
            assertTrue(updatedTask.isPresent());
            assertEquals(25, updatedTask.get().getTaskProgress());
            assertEquals("in_progress", updatedTask.get().getTaskStatus());
            assertNotNull(updatedTask.get().getStartedAt());
        }

        @Test
        @DisplayName("测试进度更新为50%")
        void testUpdateProgress_To50() {
            UpdateProgressRequest request = TestDataBuilder.buildUpdateProgressRequest(testTask.getTaskId(), 50);
            UpdateProgressResponse response = taskService.updateProgress(request);

            assertEquals(50, response.getProjectProgress());

            Optional<Task> updatedTask = taskRepository.findById(testTask.getTaskId());
            assertTrue(updatedTask.isPresent());
            assertEquals(50, updatedTask.get().getTaskProgress());
        }

        @Test
        @DisplayName("测试进度更新为100%")
        void testUpdateProgress_To100() {
            UpdateProgressRequest request = TestDataBuilder.buildUpdateProgressRequest(testTask.getTaskId(), 100);
            UpdateProgressResponse response = taskService.updateProgress(request);

            assertEquals(100, response.getProjectProgress());

            Optional<Task> updatedTask = taskRepository.findById(testTask.getTaskId());
            assertTrue(updatedTask.isPresent());
            assertEquals(100, updatedTask.get().getTaskProgress());
            assertEquals("completed", updatedTask.get().getTaskStatus());
            assertNotNull(updatedTask.get().getCompletedAt());
        }

        @Test
        @DisplayName("测试进度值超过100时抛出异常")
        void testUpdateProgress_Over100() {
            UpdateProgressRequest request = TestDataBuilder.buildUpdateProgressRequest(testTask.getTaskId(), 150);

            ProjectCollabException exception = assertThrows(
                    ProjectCollabException.class,
                    () -> taskService.updateProgress(request)
            );

            assertEquals(400, exception.getCode());
            assertTrue(exception.getMessage().contains("0-100"));
        }

        @Test
        @DisplayName("测试进度值为负数时抛出异常")
        void testUpdateProgress_Negative() {
            UpdateProgressRequest request = TestDataBuilder.buildUpdateProgressRequest(testTask.getTaskId(), -10);

            ProjectCollabException exception = assertThrows(
                    ProjectCollabException.class,
                    () -> taskService.updateProgress(request)
            );

            assertEquals(400, exception.getCode());
        }

        @Test
        @DisplayName("测试任务不存在时进度更新失败")
        void testUpdateProgress_TaskNotFound() {
            UpdateProgressRequest request = TestDataBuilder.buildUpdateProgressRequest("non_existent_task", 50);

            ProjectCollabException exception = assertThrows(
                    ProjectCollabException.class,
                    () -> taskService.updateProgress(request)
            );

            assertEquals(404, exception.getCode());
        }

        @Test
        @DisplayName("测试多次进度更新")
        void testUpdateProgress_MultipleUpdates() {
            taskService.updateProgress(TestDataBuilder.buildUpdateProgressRequest(testTask.getTaskId(), 25));
            taskService.updateProgress(TestDataBuilder.buildUpdateProgressRequest(testTask.getTaskId(), 50));
            UpdateProgressResponse response = taskService.updateProgress(
                    TestDataBuilder.buildUpdateProgressRequest(testTask.getTaskId(), 75));

            assertEquals(75, response.getProjectProgress());

            Optional<Task> updatedTask = taskRepository.findById(testTask.getTaskId());
            assertTrue(updatedTask.isPresent());
            assertEquals(75, updatedTask.get().getTaskProgress());
            assertEquals("in_progress", updatedTask.get().getTaskStatus());
        }
    }

    @Nested
    @DisplayName("进度提醒机制测试")
    class ProgressReminderTests {

        private Task testTask;

        @BeforeEach
        void setUpTask() {
            testTask = TestDataBuilder.buildAssignedTask(testProject, "development", "user_001");
            taskRepository.save(testTask);
        }

        @Test
        @DisplayName("测试进度未触发警告（进度高于阈值）")
        void testProgressReminder_AboveThreshold() {
            Stage stageWithThreshold = TestDataBuilder.buildStageWithReminders(
                    testProject, "测试阶段", "development", 1, 30, 10);
            stageRepository.save(stageWithThreshold);

            UpdateProgressRequest request = TestDataBuilder.buildUpdateProgressRequest(testTask.getTaskId(), 50);
            taskService.updateProgress(request);

            List<Reminder> reminders = reminderRepository.findByProject_ProjectId(testProject.getProjectId());
            long warningCount = reminders.stream()
                    .filter(r -> "progress_warning".equals(r.getReminderType()))
                    .count();

            assertEquals(0, warningCount);
        }

        @Test
        @DisplayName("测试进度触发警告（进度低于警告阈值）")
        void testProgressReminder_WarningThreshold() {
            Stage stageWithThreshold = TestDataBuilder.buildStageWithReminders(
                    testProject, "测试阶段", "development", 1, 70, 40);
            stageRepository.save(stageWithThreshold);

            Task task1 = TestDataBuilder.buildAssignedTask(testProject, "development", "user_001");
            taskRepository.save(task1);

            Task task2 = TestDataBuilder.buildCompletedTask(testProject, "development", "user_001");
            taskRepository.save(task2);

            UpdateProgressRequest request = TestDataBuilder.buildUpdateProgressRequest(task1.getTaskId(), 25);
            taskService.updateProgress(request);

            List<Reminder> reminders = reminderRepository.findByProject_ProjectId(testProject.getProjectId());
            assertTrue(reminders.size() > 0);
        }

        @Test
        @DisplayName("测试进度触发严重警告（进度低于严重阈值）")
        void testProgressReminder_CriticalThreshold() {
            Stage stageWithThreshold = TestDataBuilder.buildStageWithReminders(
                    testProject, "测试阶段", "development", 1, 70, 50);
            stageRepository.save(stageWithThreshold);

            Task task1 = TestDataBuilder.buildAssignedTask(testProject, "development", "user_001");
            taskRepository.save(task1);

            Task task2 = TestDataBuilder.buildAssignedTask(testProject, "development", "user_001");
            taskRepository.save(task2);

            Task task3 = TestDataBuilder.buildAssignedTask(testProject, "development", "user_001");
            taskRepository.save(task3);

            UpdateProgressRequest request = TestDataBuilder.buildUpdateProgressRequest(task1.getTaskId(), 20);
            taskService.updateProgress(request);

            List<Reminder> reminders = reminderRepository.findByProject_ProjectId(testProject.getProjectId());
            assertTrue(reminders.size() > 0);
        }

        @Test
        @DisplayName("测试提醒功能禁用时不发送提醒")
        void testProgressReminder_Disabled() {
            Stage disabledStage = TestDataBuilder.buildStage(testProject, "禁用提醒阶段", "development", 1);
            disabledStage.setProgressReminderEnabled(false);
            stageRepository.save(disabledStage);

            UpdateProgressRequest request = TestDataBuilder.buildUpdateProgressRequest(testTask.getTaskId(), 10);
            taskService.updateProgress(request);

            List<Reminder> reminders = reminderRepository.findByProject_ProjectId(testProject.getProjectId());
            long progressReminders = reminders.stream()
                    .filter(r -> r.getReminderType().startsWith("progress_"))
                    .count();

            assertEquals(0, progressReminders);
        }

        @Test
        @DisplayName("测试未设置阈值时不发送提醒")
        void testProgressReminder_NoThreshold() {
            Stage noThresholdStage = TestDataBuilder.buildStageWithReminders(
                    testProject, "无阈值阶段", "development", 1, 0, 0);
            stageRepository.save(noThresholdStage);

            UpdateProgressRequest request = TestDataBuilder.buildUpdateProgressRequest(testTask.getTaskId(), 10);
            taskService.updateProgress(request);

            List<Reminder> reminders = reminderRepository.findByProject_ProjectId(testProject.getProjectId());
            long progressReminders = reminders.stream()
                    .filter(r -> r.getReminderType().startsWith("progress_"))
                    .count();

            assertEquals(0, progressReminders);
        }
    }

    @Nested
    @DisplayName("不同阶段提醒阈值差异测试")
    class StageThresholdDifferenceTests {

        @Test
        @DisplayName("测试设计阶段的提醒阈值")
        void testStageThresholds_DesignStage() {
            Stage designStage = TestDataBuilder.buildDesignStage(testProject);
            stageRepository.save(designStage);

            assertEquals(70, designStage.getProgressWarningThreshold());
            assertEquals(50, designStage.getProgressCriticalThreshold());
            assertTrue(designStage.isProgressReminderEnabled());
        }

        @Test
        @DisplayName("测试开发阶段的提醒阈值")
        void testStageThresholds_DevelopmentStage() {
            Stage devStage = TestDataBuilder.buildDevelopmentStage(testProject);
            stageRepository.save(devStage);

            assertEquals(60, devStage.getProgressWarningThreshold());
            assertEquals(40, devStage.getProgressCriticalThreshold());
            assertTrue(devStage.isProgressReminderEnabled());
        }

        @Test
        @DisplayName("测试测试阶段的提醒阈值")
        void testStageThresholds_TestingStage() {
            Stage testStage = TestDataBuilder.buildTestingStage(testProject);
            stageRepository.save(testStage);

            assertEquals(75, testStage.getProgressWarningThreshold());
            assertEquals(50, testStage.getProgressCriticalThreshold());
            assertTrue(testStage.isProgressReminderEnabled());
        }

        @Test
        @DisplayName("测试不同阶段的阈值差异")
        void testStageThresholds_Differences() {
            Stage designStage = TestDataBuilder.buildDesignStage(testProject);
            Stage devStage = TestDataBuilder.buildDevelopmentStage(testProject);
            Stage testStage = TestDataBuilder.buildTestingStage(testProject);

            stageRepository.save(designStage);
            stageRepository.save(devStage);
            stageRepository.save(testStage);

            assertNotEquals(designStage.getProgressWarningThreshold(), devStage.getProgressWarningThreshold());
            assertNotEquals(devStage.getProgressWarningThreshold(), testStage.getProgressWarningThreshold());
            assertNotEquals(designStage.getProgressCriticalThreshold(), devStage.getProgressCriticalThreshold());
        }
    }

    @Nested
    @DisplayName("项目整体进度计算测试")
    class ProjectProgressCalculationTests {

        @Test
        @DisplayName("测试单个任务时项目进度计算")
        void testProjectProgress_SingleTask() {
            Task task = TestDataBuilder.buildAssignedTask(testProject, "development", "user_001");
            taskRepository.save(task);

            taskService.updateProgress(TestDataBuilder.buildUpdateProgressRequest(task.getTaskId(), 60));

            Project updatedProject = projectRepository.findById(testProject.getProjectId()).get();
            assertEquals(60, updatedProject.getProjectProgress());
        }

        @Test
        @DisplayName("测试多个任务时项目进度计算（平均值）")
        void testProjectProgress_MultipleTasks() {
            Task task1 = TestDataBuilder.buildAssignedTask(testProject, "development", "user_001");
            Task task2 = TestDataBuilder.buildAssignedTask(testProject, "development", "user_001");
            Task task3 = TestDataBuilder.buildAssignedTask(testProject, "development", "user_001");
            taskRepository.save(task1);
            taskRepository.save(task2);
            taskRepository.save(task3);

            taskService.updateProgress(TestDataBuilder.buildUpdateProgressRequest(task1.getTaskId(), 30));
            taskService.updateProgress(TestDataBuilder.buildUpdateProgressRequest(task2.getTaskId(), 60));
            taskService.updateProgress(TestDataBuilder.buildUpdateProgressRequest(task3.getTaskId(), 90));

            Project updatedProject = projectRepository.findById(testProject.getProjectId()).get();
            assertEquals(60, updatedProject.getProjectProgress());
        }

        @Test
        @DisplayName("测试所有任务完成时项目进度为100%")
        void testProjectProgress_AllTasksCompleted() {
            Task task1 = TestDataBuilder.buildAssignedTask(testProject, "development", "user_001");
            Task task2 = TestDataBuilder.buildAssignedTask(testProject, "development", "user_001");
            taskRepository.save(task1);
            taskRepository.save(task2);

            taskService.updateProgress(TestDataBuilder.buildUpdateProgressRequest(task1.getTaskId(), 100));
            taskService.updateProgress(TestDataBuilder.buildUpdateProgressRequest(task2.getTaskId(), 100));

            Project updatedProject = projectRepository.findById(testProject.getProjectId()).get();
            assertEquals(100, updatedProject.getProjectProgress());
            assertEquals("completed", updatedProject.getProjectStatus());
        }

        @Test
        @DisplayName("测试无任务时项目进度为0%")
        void testProjectProgress_NoTasks() {
            List<Task> allTasks = taskRepository.findByProject_ProjectId(testProject.getProjectId());
            int progress = progressService.calculateAndUpdateProjectProgress(testProject, allTasks);

            assertEquals(0, progress);
        }

        @Test
        @DisplayName("测试进度记录创建")
        void testProjectProgress_ProgressRecordCreated() {
            Task task = TestDataBuilder.buildAssignedTask(testProject, "development", "user_001");
            taskRepository.save(task);

            taskService.updateProgress(TestDataBuilder.buildUpdateProgressRequest(task.getTaskId(), 50));

            List<Progress> progressRecords = progressRepository.findByProject_ProjectIdOrderByProgressTimeDesc(
                    testProject.getProjectId());

            assertTrue(progressRecords.size() > 0);
            assertEquals(50, progressRecords.get(0).getProgressValue());
            assertNotNull(progressRecords.get(0).getProgressTime());
        }

        @Test
        @DisplayName("测试多次更新创建多条进度记录")
        void testProjectProgress_MultipleProgressRecords() {
            Task task = TestDataBuilder.buildAssignedTask(testProject, "development", "user_001");
            taskRepository.save(task);

            taskService.updateProgress(TestDataBuilder.buildUpdateProgressRequest(task.getTaskId(), 25));
            taskService.updateProgress(TestDataBuilder.buildUpdateProgressRequest(task.getTaskId(), 50));
            taskService.updateProgress(TestDataBuilder.buildUpdateProgressRequest(task.getTaskId(), 75));

            List<Progress> progressRecords = progressRepository.findByProject_ProjectIdOrderByProgressTimeDesc(
                    testProject.getProjectId());

            assertTrue(progressRecords.size() >= 3);
        }
    }
}
