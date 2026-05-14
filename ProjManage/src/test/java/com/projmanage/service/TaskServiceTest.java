package com.projmanage.service;

import com.projmanage.config.Constants;
import com.projmanage.dto.CreateTaskRequest;
import com.projmanage.exception.BusinessException;
import com.projmanage.model.Project;
import com.projmanage.model.Task;
import com.projmanage.repository.TaskRepository;
import com.projmanage.testdata.TestDataBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("TaskService 任务管理服务测试")
class TaskServiceTest {

    @Mock
    private TaskRepository taskRepository;

    @Mock
    private ProjectService projectService;

    @Mock
    private ProgressService progressService;

    @Mock
    private CollaborationService collaborationService;

    @Mock
    private RiskService riskService;

    @Mock
    private StatisticsService statisticsService;

    @Mock
    private MilestoneService milestoneService;

    @InjectMocks
    private TaskService taskService;

    private Project project;
    private Task highPriorityTask;
    private Task mediumPriorityTask;
    private Task lowPriorityTask;

    @BeforeEach
    void setUp() {
        project = TestDataBuilder.aProject()
                .withId("project_001")
                .withStatus(Constants.PROJECT_STATUS_IN_PROGRESS)
                .build();

        highPriorityTask = TestDataBuilder.aTask()
                .withId("task_001")
                .withProjectId("project_001")
                .withName("紧急任务-修复生产问题")
                .withPriority(Constants.TASK_PRIORITY_HIGH)
                .withDueDate(LocalDate.now().plusDays(1))
                .withAssignee("user_dev_01")
                .build();

        mediumPriorityTask = TestDataBuilder.aTask()
                .withId("task_002")
                .withProjectId("project_001")
                .withName("普通任务-开发新功能")
                .withPriority(Constants.TASK_PRIORITY_MEDIUM)
                .withDueDate(LocalDate.now().plusDays(3))
                .withAssignee("user_dev_02")
                .build();

        lowPriorityTask = TestDataBuilder.aTask()
                .withId("task_003")
                .withProjectId("project_001")
                .withName("低优先级任务-代码重构")
                .withPriority(Constants.TASK_PRIORITY_LOW)
                .withDueDate(LocalDate.now().plusDays(7))
                .withAssignee("user_dev_01")
                .build();
    }

    @Test
    @DisplayName("任务优先级排序测试 - 高优先级任务应排在前面")
    void testGetTasksSortedByPriority() {
        List<Task> tasks = Arrays.asList(lowPriorityTask, mediumPriorityTask, highPriorityTask);
        when(taskRepository.findByProjectId("project_001")).thenReturn(tasks);

        List<Task> sortedTasks = taskService.getTasksSortedByPriority("project_001");

        assertEquals(3, sortedTasks.size());
        assertEquals("task_001", sortedTasks.get(0).getTaskId());
        assertEquals("task_002", sortedTasks.get(1).getTaskId());
        assertEquals("task_003", sortedTasks.get(2).getTaskId());

        assertEquals(Constants.TASK_PRIORITY_HIGH, sortedTasks.get(0).getTaskPriority());
        assertEquals(Constants.TASK_PRIORITY_MEDIUM, sortedTasks.get(1).getTaskPriority());
        assertEquals(Constants.TASK_PRIORITY_LOW, sortedTasks.get(2).getTaskPriority());

        verify(taskRepository, times(1)).findByProjectId("project_001");
    }

    @Test
    @DisplayName("任务截止时间排序测试 - 截止时间早的任务应排在前面")
    void testGetTasksSortedByDueDate() {
        Task task1 = TestDataBuilder.aTask()
                .withId("task_001")
                .withDueDate(LocalDate.now().plusDays(5))
                .build();
        Task task2 = TestDataBuilder.aTask()
                .withId("task_002")
                .withDueDate(LocalDate.now().plusDays(1))
                .build();
        Task task3 = TestDataBuilder.aTask()
                .withId("task_003")
                .withDueDate(LocalDate.now().plusDays(3))
                .build();

        List<Task> tasks = Arrays.asList(task1, task2, task3);
        when(taskRepository.findByProjectId("project_001")).thenReturn(tasks);

        List<Task> sortedTasks = taskService.getTasksSortedByDueDate("project_001");

        assertEquals(3, sortedTasks.size());
        assertEquals("task_002", sortedTasks.get(0).getTaskId());
        assertEquals("task_003", sortedTasks.get(1).getTaskId());
        assertEquals("task_001", sortedTasks.get(2).getTaskId());
    }

    @Test
    @DisplayName("任务负责人负载排序测试 - 负载低的负责人任务应排在前面")
    void testGetTasksSortedByAssigneeLoad() {
        Task task1 = TestDataBuilder.aTask()
                .withId("task_001")
                .withAssignee("user_dev_01")
                .build();
        Task task2 = TestDataBuilder.aTask()
                .withId("task_002")
                .withAssignee("user_dev_02")
                .build();
        Task task3 = TestDataBuilder.aTask()
                .withId("task_003")
                .withAssignee("user_dev_01")
                .build();

        List<Task> projectTasks = Arrays.asList(task1, task2);

        Task extraTask = TestDataBuilder.aTask()
                .withId("task_004")
                .withProjectId("project_002")
                .withAssignee("user_dev_01")
                .withStatus(Constants.TASK_STATUS_IN_PROGRESS)
                .build();

        List<Task> allTasks = Arrays.asList(task1, task2, task3, extraTask);

        when(taskRepository.findByProjectId("project_001")).thenReturn(projectTasks);
        when(taskRepository.findAll()).thenReturn(allTasks);

        List<Task> sortedTasks = taskService.getTasksSortedByAssigneeLoad("project_001");

        assertEquals(2, sortedTasks.size());
        assertEquals("task_002", sortedTasks.get(0).getTaskId());
        assertEquals("task_001", sortedTasks.get(1).getTaskId());
    }

    @Test
    @DisplayName("多维度排序测试 - 优先级排序为主，截止时间排序为辅")
    void testGetTasksMultiDimensionalSort() {
        Task highPriorityEarly = TestDataBuilder.aTask()
                .withId("task_001")
                .withPriority(Constants.TASK_PRIORITY_HIGH)
                .withDueDate(LocalDate.now().plusDays(5))
                .build();
        Task highPriorityLate = TestDataBuilder.aTask()
                .withId("task_002")
                .withPriority(Constants.TASK_PRIORITY_HIGH)
                .withDueDate(LocalDate.now().plusDays(1))
                .build();
        Task mediumPriority = TestDataBuilder.aTask()
                .withId("task_003")
                .withPriority(Constants.TASK_PRIORITY_MEDIUM)
                .withDueDate(LocalDate.now().plusDays(2))
                .build();

        List<Task> tasks = Arrays.asList(highPriorityEarly, highPriorityLate, mediumPriority);
        when(taskRepository.findByProjectId("project_001")).thenReturn(tasks);
        when(taskRepository.findAll()).thenReturn(Collections.emptyList());

        List<Task> sortedTasks = taskService.getTasksMultiDimensionalSort(
                "project_001", "priority_desc", "due_date");

        assertEquals(3, sortedTasks.size());
        assertEquals("task_002", sortedTasks.get(0).getTaskId());
        assertEquals("task_001", sortedTasks.get(1).getTaskId());
        assertEquals("task_003", sortedTasks.get(2).getTaskId());
    }

    @Test
    @DisplayName("不同排序策略差异测试 - 优先级排序 vs 截止时间排序")
    void testDifferentSortingStrategies() {
        Task highPriorityLate = TestDataBuilder.aTask()
                .withId("task_001")
                .withPriority(Constants.TASK_PRIORITY_HIGH)
                .withDueDate(LocalDate.now().plusDays(10))
                .build();
        Task lowPriorityEarly = TestDataBuilder.aTask()
                .withId("task_002")
                .withPriority(Constants.TASK_PRIORITY_LOW)
                .withDueDate(LocalDate.now().plusDays(1))
                .build();

        List<Task> tasks = Arrays.asList(highPriorityLate, lowPriorityEarly);
        when(taskRepository.findByProjectId("project_001")).thenReturn(tasks);
        when(taskRepository.findAll()).thenReturn(Collections.emptyList());

        List<Task> byPriority = taskService.getTasksSortedByPriority("project_001");
        List<Task> byDueDate = taskService.getTasksSortedByDueDate("project_001");

        assertEquals("task_001", byPriority.get(0).getTaskId());
        assertEquals("task_002", byDueDate.get(0).getTaskId());

        assertNotEquals(byPriority.get(0).getTaskId(), byDueDate.get(0).getTaskId());
    }

    @Test
    @DisplayName("高优先级任务优先展示测试 - 应只返回未完成的高优先级任务")
    void testGetHighPriorityTasks() {
        Task highPriorityPending = TestDataBuilder.aTask()
                .withId("task_001")
                .withPriority(Constants.TASK_PRIORITY_HIGH)
                .withStatus(Constants.TASK_STATUS_PENDING)
                .withDueDate(LocalDate.now().plusDays(3))
                .build();
        Task highPriorityInProgress = TestDataBuilder.aTask()
                .withId("task_002")
                .withPriority(Constants.TASK_PRIORITY_HIGH)
                .withStatus(Constants.TASK_STATUS_IN_PROGRESS)
                .withDueDate(LocalDate.now().plusDays(1))
                .build();
        Task highPriorityCompleted = TestDataBuilder.aTask()
                .withId("task_003")
                .withPriority(Constants.TASK_PRIORITY_HIGH)
                .withStatus(Constants.TASK_STATUS_COMPLETED)
                .build();
        Task mediumPriority = TestDataBuilder.aTask()
                .withId("task_004")
                .withPriority(Constants.TASK_PRIORITY_MEDIUM)
                .withStatus(Constants.TASK_STATUS_PENDING)
                .build();

        List<Task> tasks = Arrays.asList(highPriorityPending, highPriorityInProgress,
                highPriorityCompleted, mediumPriority);
        when(taskRepository.findByProjectId("project_001")).thenReturn(tasks);

        List<Task> highPriorityTasks = taskService.getHighPriorityTasks("project_001");

        assertEquals(2, highPriorityTasks.size());
        assertEquals("task_002", highPriorityTasks.get(0).getTaskId());
        assertEquals("task_001", highPriorityTasks.get(1).getTaskId());

        assertTrue(highPriorityTasks.stream()
                .allMatch(t -> Constants.TASK_PRIORITY_HIGH.equals(t.getTaskPriority())));
        assertTrue(highPriorityTasks.stream()
                .noneMatch(t -> Constants.TASK_STATUS_COMPLETED.equals(t.getTaskStatus())));
    }

    @Test
    @DisplayName("空任务列表排序测试 - 应返回空列表")
    void testSortEmptyTaskList() {
        when(taskRepository.findByProjectId("project_001")).thenReturn(Collections.emptyList());

        List<Task> sortedByPriority = taskService.getTasksSortedByPriority("project_001");
        List<Task> sortedByDueDate = taskService.getTasksSortedByDueDate("project_001");
        List<Task> highPriority = taskService.getHighPriorityTasks("project_001");
        List<Task> multiSort = taskService.getTasksMultiDimensionalSort(
                "project_001", "priority_desc", "due_date");

        assertTrue(sortedByPriority.isEmpty());
        assertTrue(sortedByDueDate.isEmpty());
        assertTrue(highPriority.isEmpty());
        assertTrue(multiSort.isEmpty());
    }

    @Test
    @DisplayName("任务创建测试 - 项目不存在应抛出异常")
    void testCreateTaskProjectNotFound() {
        when(projectService.getProjectById("project_999")).thenReturn(Optional.empty());

        CreateTaskRequest request = new CreateTaskRequest();
        request.setProjectId("project_999");
        request.setTaskName("测试任务");

        assertThrows(BusinessException.class, () -> taskService.createTask(request));
        verify(taskRepository, never()).save(any());
    }

    @Test
    @DisplayName("任务创建测试 - 项目已完成应抛出异常")
    void testCreateTaskProjectCompleted() {
        Project completedProject = TestDataBuilder.aProject()
                .withId("project_001")
                .withStatus(Constants.PROJECT_STATUS_COMPLETED)
                .build();

        when(projectService.getProjectById("project_001")).thenReturn(Optional.of(completedProject));

        CreateTaskRequest request = new CreateTaskRequest();
        request.setProjectId("project_001");
        request.setTaskName("测试任务");

        BusinessException exception = assertThrows(BusinessException.class,
                () -> taskService.createTask(request));
        assertEquals(400, exception.getCode());
        verify(taskRepository, never()).save(any());
    }

    @Test
    @DisplayName("任务创建测试 - 负责人不是项目成员应抛出异常")
    void testCreateTaskInvalidAssignee() {
        when(projectService.getProjectById("project_001")).thenReturn(Optional.of(project));
        when(projectService.isMemberOfProject("project_001", "unknown_user")).thenReturn(false);

        CreateTaskRequest request = new CreateTaskRequest();
        request.setProjectId("project_001");
        request.setTaskName("测试任务");
        request.setTaskAssignee("unknown_user");

        BusinessException exception = assertThrows(BusinessException.class,
                () -> taskService.createTask(request));
        assertEquals(400, exception.getCode());
        verify(taskRepository, never()).save(any());
    }

    @Test
    @DisplayName("任务创建测试 - 成功创建任务")
    void testCreateTaskSuccess() {
        when(projectService.getProjectById("project_001")).thenReturn(Optional.of(project));
        when(projectService.isMemberOfProject("project_001", "user_dev_01")).thenReturn(true);
        when(taskRepository.save(any())).thenReturn(highPriorityTask);

        CreateTaskRequest request = new CreateTaskRequest();
        request.setProjectId("project_001");
        request.setTaskName("测试任务");
        request.setTaskAssignee("user_dev_01");
        request.setTaskPriority(Constants.TASK_PRIORITY_HIGH);

        String taskId = taskService.createTask(request);

        assertNotNull(taskId);
        verify(taskRepository, times(1)).save(any());
        verify(collaborationService, times(1)).sendNotification(
                eq("user_dev_01"), any(), any(), any(), any(), any());
        verify(progressService, times(1)).updateProgress("project_001");
        verify(statisticsService, times(1)).updateTaskStatistics("project_001");
    }

    @Test
    @DisplayName("任务进度更新测试 - 任务不存在应抛出异常")
    void testUpdateTaskProgressTaskNotFound() {
        when(taskRepository.findById("task_999")).thenReturn(Optional.empty());

        assertThrows(BusinessException.class,
                () -> taskService.updateTaskProgress("task_999", 50, null));
        verify(taskRepository, never()).save(any());
    }

    @Test
    @DisplayName("任务进度更新测试 - 任务已完成应抛出异常")
    void testUpdateTaskProgressTaskCompleted() {
        Task completedTask = TestDataBuilder.aTask()
                .withId("task_001")
                .withStatus(Constants.TASK_STATUS_COMPLETED)
                .build();

        when(taskRepository.findById("task_001")).thenReturn(Optional.of(completedTask));

        BusinessException exception = assertThrows(BusinessException.class,
                () -> taskService.updateTaskProgress("task_001", 50, null));
        assertEquals(400, exception.getCode());
    }

    @Test
    @DisplayName("任务进度更新测试 - 进度值超出范围应抛出异常")
    void testUpdateTaskProgressInvalidProgress() {
        when(taskRepository.findById("task_001")).thenReturn(Optional.of(highPriorityTask));

        assertThrows(BusinessException.class,
                () -> taskService.updateTaskProgress("task_001", 150, null));
        assertThrows(BusinessException.class,
                () -> taskService.updateTaskProgress("task_001", -10, null));
    }

    @Test
    @DisplayName("任务进度更新测试 - 进度达到100%应标记任务为已完成")
    void testUpdateTaskProgressComplete() {
        when(taskRepository.findById("task_001")).thenReturn(Optional.of(highPriorityTask));

        taskService.updateTaskProgress("task_001", 100, 40);

        verify(taskRepository, times(1)).save(argThat(task ->
                Constants.TASK_STATUS_COMPLETED.equals(task.getTaskStatus()) &&
                task.getCompletedAt() != null
        ));
        verify(progressService, times(1)).updateProgress(any());
        verify(riskService, times(1)).checkTaskRisk(any());
        verify(statisticsService, times(1)).updateTaskStatistics(any());
    }

    @Test
    @DisplayName("任务进度更新测试 - 进度在0-100之间应更新状态为进行中")
    void testUpdateTaskProgressInProgress() {
        when(taskRepository.findById("task_001")).thenReturn(Optional.of(highPriorityTask));

        taskService.updateTaskProgress("task_001", 50, 20);

        verify(taskRepository, times(1)).save(argThat(task ->
                Constants.TASK_STATUS_IN_PROGRESS.equals(task.getTaskStatus())
        ));
    }
}
