package com.projectcollab.service;

import com.projectcollab.builder.TestDataBuilder;
import com.projectcollab.dto.CreateTaskRequest;
import com.projectcollab.dto.CreateTaskResponse;
import com.projectcollab.entity.Project;
import com.projectcollab.entity.ProjectMember;
import com.projectcollab.entity.Stage;
import com.projectcollab.entity.Task;
import com.projectcollab.exception.ProjectCollabException;
import com.projectcollab.repository.ProjectMemberRepository;
import com.projectcollab.repository.ProjectRepository;
import com.projectcollab.repository.StageRepository;
import com.projectcollab.repository.TaskRepository;
import com.projectcollab.service.task.TaskService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;

import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
@DisplayName("任务模块单元测试")
class TaskModuleTest {

    @Autowired
    private TaskService taskService;

    @Autowired
    private TaskRepository taskRepository;

    @Autowired
    private ProjectRepository projectRepository;

    @Autowired
    private ProjectMemberRepository memberRepository;

    @Autowired
    private StageRepository stageRepository;

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
    @DisplayName("任务创建与分配测试")
    class TaskCreationTests {

        @Test
        @DisplayName("测试任务创建成功")
        void testCreateTask_Success() {
            CreateTaskRequest request = TestDataBuilder.buildCreateTaskRequest(testProject.getProjectId());

            CreateTaskResponse response = taskService.createTask(request);

            assertNotNull(response);
            assertNotNull(response.getTaskId());
            assertEquals("assigned", response.getStatus());

            Optional<Task> savedTask = taskRepository.findById(response.getTaskId());
            assertTrue(savedTask.isPresent());
            assertEquals("新任务", savedTask.get().getTaskName());
            assertEquals("development", savedTask.get().getTaskStage());
        }

        @Test
        @DisplayName("测试任务创建时自动分配成员")
        void testCreateTask_AutoAssignment() {
            ProjectMember member2 = TestDataBuilder.buildMember(testProject, "user_002");
            memberRepository.save(member2);

            CreateTaskRequest request = TestDataBuilder.buildCreateTaskRequest(testProject.getProjectId());
            CreateTaskResponse response = taskService.createTask(request);

            Optional<Task> savedTask = taskRepository.findById(response.getTaskId());
            assertTrue(savedTask.isPresent());
            assertNotNull(savedTask.get().getTaskAssignee());
            assertTrue(
                savedTask.get().getTaskAssignee().equals("user_001") || 
                savedTask.get().getTaskAssignee().equals("user_002")
            );
        }

        @Test
        @DisplayName("测试任务创建时设置默认优先级")
        void testCreateTask_DefaultPriority() {
            CreateTaskRequest request = TestDataBuilder.buildCreateTaskRequest(testProject.getProjectId());
            CreateTaskResponse response = taskService.createTask(request);

            Optional<Task> savedTask = taskRepository.findById(response.getTaskId());
            assertTrue(savedTask.isPresent());
            assertEquals("normal", savedTask.get().getTaskPriority());
            assertFalse(savedTask.get().isLocked());
        }

        @Test
        @DisplayName("测试任务创建时指定优先级")
        void testCreateTask_SpecifiedPriority() {
            CreateTaskRequest request = TestDataBuilder.buildCriticalPriorityTaskRequest(testProject.getProjectId());
            CreateTaskResponse response = taskService.createTask(request);

            Optional<Task> savedTask = taskRepository.findById(response.getTaskId());
            assertTrue(savedTask.isPresent());
            assertEquals("critical", savedTask.get().getTaskPriority());
        }

        @Test
        @DisplayName("测试项目不存在时创建任务失败")
        void testCreateTask_ProjectNotFound() {
            CreateTaskRequest request = TestDataBuilder.buildCreateTaskRequest("non_existent_project");

            ProjectCollabException exception = assertThrows(
                    ProjectCollabException.class,
                    () -> taskService.createTask(request)
            );

            assertEquals(404, exception.getCode());
        }

        @Test
        @DisplayName("测试项目成员不足时创建任务失败")
        void testCreateTask_NoMembers() {
            memberRepository.deleteAll();

            CreateTaskRequest request = TestDataBuilder.buildCreateTaskRequest(testProject.getProjectId());

            ProjectCollabException exception = assertThrows(
                    ProjectCollabException.class,
                    () -> taskService.createTask(request)
            );

            assertTrue(exception.getMessage().contains("成员不足") || exception.getMessage().contains("可用"));
        }
    }

    @Nested
    @DisplayName("任务锁定机制测试")
    class TaskLockingTests {

        private Task testTask;

        @BeforeEach
        void setUpTask() {
            testTask = TestDataBuilder.buildAssignedTask(testProject, "development", "user_001");
            taskRepository.save(testTask);
        }

        @Test
        @DisplayName("测试任务锁定成功")
        void testLockTask_Success() {
            boolean result = taskService.lockTask(testTask.getTaskId(), "user_001", "normal");

            assertTrue(result);

            Optional<Task> lockedTask = taskRepository.findById(testTask.getTaskId());
            assertTrue(lockedTask.isPresent());
            assertTrue(lockedTask.get().isLocked());
            assertEquals("user_001", lockedTask.get().getLockOwner());
            assertNotNull(lockedTask.get().getLockedAt());
        }

        @Test
        @DisplayName("测试任务已被其他用户锁定时返回false")
        void testLockTask_AlreadyLocked() {
            taskService.lockTask(testTask.getTaskId(), "user_001", "normal");

            boolean result = taskService.lockTask(testTask.getTaskId(), "user_002", "normal");

            assertFalse(result);
        }

        @Test
        @DisplayName("测试同一用户重复锁定返回true")
        void testLockTask_SameUserReLock() {
            taskService.lockTask(testTask.getTaskId(), "user_001", "normal");

            boolean result = taskService.lockTask(testTask.getTaskId(), "user_001", "normal");

            assertTrue(result);
        }

        @Test
        @DisplayName("测试任务锁定超时后可重新锁定")
        void testLockTask_ExpiredLock() {
            Task expiredLockTask = TestDataBuilder.buildExpiredLockTask(
                    testProject, "development", "user_001", "user_001");
            taskRepository.save(expiredLockTask);

            boolean result = taskService.lockTask(expiredLockTask.getTaskId(), "user_002", "normal");

            assertTrue(result);
        }

        @Test
        @DisplayName("测试任务解锁成功")
        void testUnlockTask_Success() {
            taskService.lockTask(testTask.getTaskId(), "user_001", "normal");

            boolean result = taskService.unlockTask(testTask.getTaskId(), "user_001");

            assertTrue(result);

            Optional<Task> unlockedTask = taskRepository.findById(testTask.getTaskId());
            assertTrue(unlockedTask.isPresent());
            assertFalse(unlockedTask.get().isLocked());
        }

        @Test
        @DisplayName("测试非锁定者无法解锁任务")
        void testUnlockTask_WrongUser() {
            taskService.lockTask(testTask.getTaskId(), "user_001", "normal");

            ProjectCollabException exception = assertThrows(
                    ProjectCollabException.class,
                    () -> taskService.unlockTask(testTask.getTaskId(), "user_002")
            );

            assertEquals(403, exception.getCode());
        }

        @Test
        @DisplayName("测试未锁定的任务解锁返回true")
        void testUnlockTask_NotLocked() {
            boolean result = taskService.unlockTask(testTask.getTaskId(), "user_001");

            assertTrue(result);
        }
    }

    @Nested
    @DisplayName("任务优先级与锁定超时测试")
    class TaskPriorityAndTimeoutTests {

        @Test
        @DisplayName("测试紧急任务锁定超时时间为300秒")
        void testLockTimeout_CriticalPriority() {
            int timeout = taskService.getLockTimeoutForPriority("critical");
            assertEquals(300, timeout);
        }

        @Test
        @DisplayName("测试高优先级任务锁定超时时间为600秒")
        void testLockTimeout_HighPriority() {
            int timeout = taskService.getLockTimeoutForPriority("high");
            assertEquals(600, timeout);
        }

        @Test
        @DisplayName("测试普通任务锁定超时时间为1200秒")
        void testLockTimeout_NormalPriority() {
            int timeout = taskService.getLockTimeoutForPriority("normal");
            assertEquals(1200, timeout);
        }

        @Test
        @DisplayName("测试低优先级任务锁定超时时间为1800秒")
        void testLockTimeout_LowPriority() {
            int timeout = taskService.getLockTimeoutForPriority("low");
            assertEquals(1800, timeout);
        }

        @Test
        @DisplayName("测试未知优先级使用默认超时时间")
        void testLockTimeout_UnknownPriority() {
            int timeout = taskService.getLockTimeoutForPriority("unknown");
            assertEquals(1200, timeout);
        }

        @Test
        @DisplayName("测试不同优先级任务的锁定超时时间差异")
        void testLockTimeout_DifferencesBetweenPriorities() {
            int criticalTimeout = taskService.getLockTimeoutForPriority("critical");
            int normalTimeout = taskService.getLockTimeoutForPriority("normal");
            int lowTimeout = taskService.getLockTimeoutForPriority("low");

            assertTrue(criticalTimeout < normalTimeout, "紧急任务超时时间应小于普通任务");
            assertTrue(normalTimeout < lowTimeout, "普通任务超时时间应小于低优先级任务");
        }

        @Test
        @DisplayName("测试创建不同优先级的任务")
        void testCreateTask_AllPriorities() {
            CreateTaskRequest criticalRequest = TestDataBuilder.buildCriticalPriorityTaskRequest(testProject.getProjectId());
            CreateTaskRequest highRequest = TestDataBuilder.buildHighPriorityTaskRequest(testProject.getProjectId());
            CreateTaskRequest lowRequest = TestDataBuilder.buildLowPriorityTaskRequest(testProject.getProjectId());

            CreateTaskResponse criticalResponse = taskService.createTask(criticalRequest);
            CreateTaskResponse highResponse = taskService.createTask(highRequest);
            CreateTaskResponse lowResponse = taskService.createTask(lowRequest);

            assertEquals("critical", taskRepository.findById(criticalResponse.getTaskId()).get().getTaskPriority());
            assertEquals("high", taskRepository.findById(highResponse.getTaskId()).get().getTaskPriority());
            assertEquals("low", taskRepository.findById(lowResponse.getTaskId()).get().getTaskPriority());
        }
    }

    @Nested
    @DisplayName("任务状态生命周期测试")
    class TaskStatusLifecycleTests {

        @Test
        @DisplayName("测试完整状态生命周期：已分配 -> 执行中 -> 已完成")
        void testTaskStatusLifecycle_FullCycle() {
            Task task = TestDataBuilder.buildAssignedTask(testProject, "development", "user_001");
            taskRepository.save(task);

            assertEquals("assigned", task.getTaskStatus(), "初始状态应为assigned");

            Task inProgressTask = taskService.startTask(task.getTaskId());
            assertEquals("in_progress", inProgressTask.getTaskStatus(), "开始后状态应为in_progress");
            assertNotNull(inProgressTask.getStartedAt(), "应有开始时间");

            Task completedTask = taskService.completeTask(task.getTaskId());
            assertEquals("completed", completedTask.getTaskStatus(), "完成后状态应为completed");
            assertEquals(100, completedTask.getTaskProgress(), "进度应为100%");
            assertNotNull(completedTask.getCompletedAt(), "应有完成时间");
        }

        @Test
        @DisplayName("测试更新进度触发状态变为执行中")
        void testTaskStatus_UpdateProgressToInProgress() {
            Task task = TestDataBuilder.buildAssignedTask(testProject, "development", "user_001");
            taskRepository.save(task);

            com.projectcollab.dto.UpdateProgressRequest request = 
                    TestDataBuilder.buildUpdateProgressRequest(task.getTaskId(), 25);
            taskService.updateProgress(request);

            Optional<Task> updatedTask = taskRepository.findById(task.getTaskId());
            assertTrue(updatedTask.isPresent());
            assertEquals("in_progress", updatedTask.get().getTaskStatus());
            assertEquals(25, updatedTask.get().getTaskProgress());
        }

        @Test
        @DisplayName("测试进度达到100%触发状态变为已完成")
        void testTaskStatus_Progress100CompletesTask() {
            Task task = TestDataBuilder.buildInProgressTask(testProject, "development", "user_001", 50);
            taskRepository.save(task);

            com.projectcollab.dto.UpdateProgressRequest request = 
                    TestDataBuilder.buildUpdateProgressRequest(task.getTaskId(), 100);
            taskService.updateProgress(request);

            Optional<Task> updatedTask = taskRepository.findById(task.getTaskId());
            assertTrue(updatedTask.isPresent());
            assertEquals("completed", updatedTask.get().getTaskStatus());
            assertEquals(100, updatedTask.get().getTaskProgress());
        }

        @Test
        @DisplayName("测试已完成任务无法更新进度")
        void testTaskStatus_CompletedTaskCannotUpdateProgress() {
            Task task = TestDataBuilder.buildCompletedTask(testProject, "development", "user_001");
            taskRepository.save(task);

            com.projectcollab.dto.UpdateProgressRequest request = 
                    TestDataBuilder.buildUpdateProgressRequest(task.getTaskId(), 50);

            ProjectCollabException exception = assertThrows(
                    ProjectCollabException.class,
                    () -> taskService.updateProgress(request)
            );

            assertEquals(400, exception.getCode());
            assertTrue(exception.getMessage().contains("任务已完成"));
        }

        @Test
        @DisplayName("测试未分配状态的任务无法开始")
        void testTaskStatus_PendingTaskCannotStart() {
            Task task = TestDataBuilder.buildPendingTask(testProject, "development");
            taskRepository.save(task);

            ProjectCollabException exception = assertThrows(
                    ProjectCollabException.class,
                    () -> taskService.startTask(task.getTaskId())
            );

            assertEquals(400, exception.getCode());
        }

        @Test
        @DisplayName("测试已完成任务无法再次完成")
        void testTaskStatus_CompletedTaskCannotCompleteAgain() {
            Task task = TestDataBuilder.buildCompletedTask(testProject, "development", "user_001");
            taskRepository.save(task);

            ProjectCollabException exception = assertThrows(
                    ProjectCollabException.class,
                    () -> taskService.completeTask(task.getTaskId())
            );

            assertEquals(400, exception.getCode());
            assertTrue(exception.getMessage().contains("已经完成"));
        }
    }

    @Nested
    @DisplayName("成员分配可用性测试")
    class MemberAvailabilityTests {

        @Test
        @DisplayName("测试活跃成员可以被分配任务")
        void testMemberAvailability_ActiveMember() {
            ProjectMember activeMember = TestDataBuilder.buildMember(testProject, "active_user");
            memberRepository.save(activeMember);

            CreateTaskRequest request = TestDataBuilder.buildCreateTaskRequest(testProject.getProjectId());
            CreateTaskResponse response = taskService.createTask(request);

            assertNotNull(response.getTaskId());
        }

        @Test
        @DisplayName("测试任务数量最少的成员优先被分配")
        void testMemberAvailability_LeastTaskCountFirst() {
            ProjectMember busyMember = TestDataBuilder.buildMemberWithTasks(testProject, "busy_user", 5);
            ProjectMember freeMember = TestDataBuilder.buildMemberWithTasks(testProject, "free_user", 0);
            memberRepository.save(busyMember);
            memberRepository.save(freeMember);

            CreateTaskRequest request = TestDataBuilder.buildCreateTaskRequest(testProject.getProjectId());
            CreateTaskResponse response = taskService.createTask(request);

            Optional<Task> savedTask = taskRepository.findById(response.getTaskId());
            assertTrue(savedTask.isPresent());
            assertEquals("free_user", savedTask.get().getTaskAssignee());
        }

        @Test
        @DisplayName("测试任务数量过多的成员不会被选中")
        void testMemberAvailability_MaxTaskCount() {
            ProjectMember overloadedMember = TestDataBuilder.buildMemberWithTasks(testProject, "overloaded", 15);
            memberRepository.save(overloadedMember);

            CreateTaskRequest request = TestDataBuilder.buildCreateTaskRequest(testProject.getProjectId());

            ProjectCollabException exception = assertThrows(
                    ProjectCollabException.class,
                    () -> taskService.createTask(request)
            );

            assertTrue(exception.getMessage().contains("可用"));
        }
    }
}
