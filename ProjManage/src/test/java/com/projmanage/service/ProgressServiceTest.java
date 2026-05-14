package com.projmanage.service;

import com.projmanage.config.Constants;
import com.projmanage.dto.ProgressResponse;
import com.projmanage.model.Progress;
import com.projmanage.model.Task;
import com.projmanage.repository.ProgressRepository;
import com.projmanage.repository.TaskRepository;
import com.projmanage.testdata.TestDataBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("ProgressService 进度监控服务测试")
class ProgressServiceTest {

    @Mock
    private ProgressRepository progressRepository;

    @Mock
    private TaskRepository taskRepository;

    @InjectMocks
    private ProgressService progressService;

    private List<Task> tasks;

    @BeforeEach
    void setUp() {
        Task completedTask1 = TestDataBuilder.aTask()
                .withId("task_001")
                .withProjectId("project_001")
                .withStatus(Constants.TASK_STATUS_COMPLETED)
                .withProgress(100)
                .withName("已完成任务1")
                .build();

        Task completedTask2 = TestDataBuilder.aTask()
                .withId("task_002")
                .withProjectId("project_001")
                .withStatus(Constants.TASK_STATUS_COMPLETED)
                .withProgress(100)
                .withName("已完成任务2")
                .build();

        Task inProgressTask = TestDataBuilder.aTask()
                .withId("task_003")
                .withProjectId("project_001")
                .withStatus(Constants.TASK_STATUS_IN_PROGRESS)
                .withProgress(60)
                .withName("进行中任务")
                .build();

        Task pendingTask = TestDataBuilder.aTask()
                .withId("task_004")
                .withProjectId("project_001")
                .withStatus(Constants.TASK_STATUS_PENDING)
                .withProgress(0)
                .withName("待开始任务")
                .build();

        tasks = Arrays.asList(completedTask1, completedTask2, inProgressTask, pendingTask);
    }

    @Test
    @DisplayName("进度自动统计测试 - 应正确计算任务统计")
    void testUpdateProgressCalculation() {
        when(taskRepository.findByProjectId("project_001")).thenReturn(tasks);
        when(progressRepository.findByProjectId("project_001")).thenReturn(Optional.empty());

        progressService.updateProgress("project_001");

        ArgumentCaptor<Progress> progressCaptor = ArgumentCaptor.forClass(Progress.class);
        verify(progressRepository, times(1)).save(progressCaptor.capture());

        Progress savedProgress = progressCaptor.getValue();
        assertEquals("project_001", savedProgress.getProjectId());
        assertEquals(4, savedProgress.getTotalTasks());
        assertEquals(2, savedProgress.getCompletedTasks());
        assertEquals(1, savedProgress.getInProgressTasks());
        assertEquals(1, savedProgress.getPendingTasks());

        int expectedProgress = (100 + 100 + 60 + 0) / 4;
        assertEquals(expectedProgress, savedProgress.getOverallProgress());
    }

    @Test
    @DisplayName("进度自动统计测试 - 应更新现有进度记录")
    void testUpdateProgressUpdateExisting() {
        Progress existingProgress = TestDataBuilder.aProgress()
                .withId("progress_001")
                .withProjectId("project_001")
                .withTotalTasks(2)
                .withCompletedTasks(1)
                .withOverallProgress(50)
                .build();

        when(taskRepository.findByProjectId("project_001")).thenReturn(tasks);
        when(progressRepository.findByProjectId("project_001")).thenReturn(Optional.of(existingProgress));

        progressService.updateProgress("project_001");

        ArgumentCaptor<Progress> progressCaptor = ArgumentCaptor.forClass(Progress.class);
        verify(progressRepository, times(1)).save(progressCaptor.capture());

        Progress savedProgress = progressCaptor.getValue();
        assertEquals("progress_001", savedProgress.getProgressId());
        assertEquals(4, savedProgress.getTotalTasks());
        assertEquals(2, savedProgress.getCompletedTasks());
    }

    @Test
    @DisplayName("进度查询测试 - 应返回正确的进度响应")
    void testGetProjectProgress() {
        when(taskRepository.findByProjectId("project_001")).thenReturn(tasks);
        when(progressRepository.findByProjectId("project_001")).thenReturn(Optional.empty());
        when(progressRepository.save(any(Progress.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ProgressResponse response = progressService.getProjectProgress("project_001");

        assertEquals(4, response.getTotalTasks());
        assertEquals(2, response.getCompletedTasks());
        assertEquals(1, response.getInProgressTasks());
        assertEquals(1, response.getPendingTasks());

        int expectedProgress = (100 + 100 + 60 + 0) / 4;
        assertEquals(expectedProgress, response.getOverallProgress());
    }

    @Test
    @DisplayName("进度统计测试 - 空项目应返回0进度")
    void testUpdateProgressEmptyProject() {
        when(taskRepository.findByProjectId("project_001")).thenReturn(Collections.emptyList());
        when(progressRepository.findByProjectId("project_001")).thenReturn(Optional.empty());

        progressService.updateProgress("project_001");

        ArgumentCaptor<Progress> progressCaptor = ArgumentCaptor.forClass(Progress.class);
        verify(progressRepository, times(1)).save(progressCaptor.capture());

        Progress savedProgress = progressCaptor.getValue();
        assertEquals(0, savedProgress.getTotalTasks());
        assertEquals(0, savedProgress.getCompletedTasks());
        assertEquals(0, savedProgress.getOverallProgress());
    }

    @Test
    @DisplayName("进度统计测试 - 全部完成的项目应返回100%进度")
    void testUpdateProgressAllCompleted() {
        Task completed1 = TestDataBuilder.aTask()
                .withId("task_001")
                .withStatus(Constants.TASK_STATUS_COMPLETED)
                .withProgress(100)
                .build();
        Task completed2 = TestDataBuilder.aTask()
                .withId("task_002")
                .withStatus(Constants.TASK_STATUS_COMPLETED)
                .withProgress(100)
                .build();

        when(taskRepository.findByProjectId("project_001")).thenReturn(Arrays.asList(completed1, completed2));
        when(progressRepository.findByProjectId("project_001")).thenReturn(Optional.empty());

        progressService.updateProgress("project_001");

        ArgumentCaptor<Progress> progressCaptor = ArgumentCaptor.forClass(Progress.class);
        verify(progressRepository, times(1)).save(progressCaptor.capture());

        Progress savedProgress = progressCaptor.getValue();
        assertEquals(2, savedProgress.getTotalTasks());
        assertEquals(2, savedProgress.getCompletedTasks());
        assertEquals(0, savedProgress.getInProgressTasks());
        assertEquals(0, savedProgress.getPendingTasks());
        assertEquals(100, savedProgress.getOverallProgress());
    }

    @Test
    @DisplayName("进度统计测试 - 未开始项目应返回0进度")
    void testUpdateProgressAllPending() {
        Task pending1 = TestDataBuilder.aTask()
                .withId("task_001")
                .withStatus(Constants.TASK_STATUS_PENDING)
                .withProgress(0)
                .build();
        Task pending2 = TestDataBuilder.aTask()
                .withId("task_002")
                .withStatus(Constants.TASK_STATUS_PENDING)
                .withProgress(0)
                .build();

        when(taskRepository.findByProjectId("project_001")).thenReturn(Arrays.asList(pending1, pending2));
        when(progressRepository.findByProjectId("project_001")).thenReturn(Optional.empty());

        progressService.updateProgress("project_001");

        ArgumentCaptor<Progress> progressCaptor = ArgumentCaptor.forClass(Progress.class);
        verify(progressRepository, times(1)).save(progressCaptor.capture());

        Progress savedProgress = progressCaptor.getValue();
        assertEquals(2, savedProgress.getTotalTasks());
        assertEquals(0, savedProgress.getCompletedTasks());
        assertEquals(0, savedProgress.getInProgressTasks());
        assertEquals(2, savedProgress.getPendingTasks());
        assertEquals(0, savedProgress.getOverallProgress());
    }

    @Test
    @DisplayName("进度统计测试 - 空进度查询应返回默认值")
    void testGetProjectProgressEmpty() {
        when(taskRepository.findByProjectId("project_999")).thenReturn(Collections.emptyList());
        when(progressRepository.findByProjectId("project_999")).thenReturn(Optional.empty());
        when(progressRepository.save(any(Progress.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ProgressResponse response = progressService.getProjectProgress("project_999");

        assertEquals(0, response.getTotalTasks());
        assertEquals(0, response.getCompletedTasks());
        assertEquals(0, response.getInProgressTasks());
        assertEquals(0, response.getPendingTasks());
        assertEquals(0, response.getOverallProgress());
    }

    @Test
    @DisplayName("进度一致性测试 - 进度统计应与任务状态保持一致")
    void testProgressConsistencyWithTaskStatus() {
        Task task50 = TestDataBuilder.aTask()
                .withId("task_001")
                .withStatus(Constants.TASK_STATUS_IN_PROGRESS)
                .withProgress(50)
                .build();
        Task task75 = TestDataBuilder.aTask()
                .withId("task_002")
                .withStatus(Constants.TASK_STATUS_IN_PROGRESS)
                .withProgress(75)
                .build();

        List<Task> tasks = Arrays.asList(task50, task75);
        when(taskRepository.findByProjectId("project_001")).thenReturn(tasks);
        when(progressRepository.findByProjectId("project_001")).thenReturn(Optional.empty());

        progressService.updateProgress("project_001");

        ArgumentCaptor<Progress> progressCaptor = ArgumentCaptor.forClass(Progress.class);
        verify(progressRepository, times(1)).save(progressCaptor.capture());

        Progress savedProgress = progressCaptor.getValue();

        int inProgressCount = (int) tasks.stream()
                .filter(t -> Constants.TASK_STATUS_IN_PROGRESS.equals(t.getTaskStatus()))
                .count();
        assertEquals(inProgressCount, savedProgress.getInProgressTasks());

        int avgProgress = (50 + 75) / 2;
        assertEquals(avgProgress, savedProgress.getOverallProgress());
    }

    @Test
    @DisplayName("不同项目活跃程度测试 - 活跃项目应频繁更新进度")
    void testProgressUpdateForActiveProject() {
        Task task1 = TestDataBuilder.aTask()
                .withId("task_001")
                .withStatus(Constants.TASK_STATUS_IN_PROGRESS)
                .withProgress(30)
                .build();
        Task task2 = TestDataBuilder.aTask()
                .withId("task_002")
                .withStatus(Constants.TASK_STATUS_IN_PROGRESS)
                .withProgress(60)
                .build();
        Task task3 = TestDataBuilder.aTask()
                .withId("task_003")
                .withStatus(Constants.TASK_STATUS_PENDING)
                .withProgress(0)
                .build();

        List<Task> activeTasks = Arrays.asList(task1, task2, task3);
        when(taskRepository.findByProjectId("active_project")).thenReturn(activeTasks);
        when(progressRepository.findByProjectId("active_project")).thenReturn(Optional.empty());
        when(progressRepository.save(any(Progress.class))).thenAnswer(invocation -> invocation.getArgument(0));

        progressService.updateProgress("active_project");

        ArgumentCaptor<Progress> progressCaptor = ArgumentCaptor.forClass(Progress.class);
        verify(progressRepository, times(1)).save(progressCaptor.capture());

        Progress savedProgress = progressCaptor.getValue();
        assertEquals(3, savedProgress.getTotalTasks());
        assertEquals(2, savedProgress.getInProgressTasks());

        int expectedProgress = (30 + 60 + 0) / 3;
        assertEquals(expectedProgress, savedProgress.getOverallProgress());
    }

    @Test
    @DisplayName("不同项目活跃程度测试 - 低活跃项目应正确计算进度")
    void testProgressUpdateForLessActiveProject() {
        Task task1 = TestDataBuilder.aTask()
                .withId("task_001")
                .withStatus(Constants.TASK_STATUS_PENDING)
                .withProgress(0)
                .build();
        Task task2 = TestDataBuilder.aTask()
                .withId("task_002")
                .withStatus(Constants.TASK_STATUS_PENDING)
                .withProgress(0)
                .build();

        List<Task> lessActiveTasks = Arrays.asList(task1, task2);
        when(taskRepository.findByProjectId("inactive_project")).thenReturn(lessActiveTasks);
        when(progressRepository.findByProjectId("inactive_project")).thenReturn(Optional.empty());

        progressService.updateProgress("inactive_project");

        ArgumentCaptor<Progress> progressCaptor = ArgumentCaptor.forClass(Progress.class);
        verify(progressRepository, times(1)).save(progressCaptor.capture());

        Progress savedProgress = progressCaptor.getValue();
        assertEquals(2, savedProgress.getTotalTasks());
        assertEquals(0, savedProgress.getCompletedTasks());
        assertEquals(0, savedProgress.getInProgressTasks());
        assertEquals(2, savedProgress.getPendingTasks());
        assertEquals(0, savedProgress.getOverallProgress());
    }

    @Test
    @DisplayName("进度更新时间戳测试 - 每次更新应设置新的时间戳")
    void testProgressUpdateTimestamp() {
        Progress existingProgress = TestDataBuilder.aProgress()
                .withId("progress_001")
                .withProjectId("project_001")
                .build();

        when(taskRepository.findByProjectId("project_001")).thenReturn(tasks);
        when(progressRepository.findByProjectId("project_001")).thenReturn(Optional.of(existingProgress));

        progressService.updateProgress("project_001");

        ArgumentCaptor<Progress> progressCaptor = ArgumentCaptor.forClass(Progress.class);
        verify(progressRepository, times(1)).save(progressCaptor.capture());

        Progress savedProgress = progressCaptor.getValue();
        assertNotNull(savedProgress.getUpdatedAt());
    }

    @Test
    @DisplayName("空进度值处理测试 - 进度为null应按0处理")
    void testNullProgressHandling() {
        Task taskWithNullProgress = TestDataBuilder.aTask()
                .withId("task_001")
                .withStatus(Constants.TASK_STATUS_PENDING)
                .withProgress(null)
                .build();
        Task taskWithProgress = TestDataBuilder.aTask()
                .withId("task_002")
                .withStatus(Constants.TASK_STATUS_IN_PROGRESS)
                .withProgress(50)
                .build();

        List<Task> tasksWithNull = Arrays.asList(taskWithNullProgress, taskWithProgress);
        when(taskRepository.findByProjectId("project_001")).thenReturn(tasksWithNull);
        when(progressRepository.findByProjectId("project_001")).thenReturn(Optional.empty());

        progressService.updateProgress("project_001");

        ArgumentCaptor<Progress> progressCaptor = ArgumentCaptor.forClass(Progress.class);
        verify(progressRepository, times(1)).save(progressCaptor.capture());

        Progress savedProgress = progressCaptor.getValue();
        int expectedProgress = (0 + 50) / 2;
        assertEquals(expectedProgress, savedProgress.getOverallProgress());
    }
}
