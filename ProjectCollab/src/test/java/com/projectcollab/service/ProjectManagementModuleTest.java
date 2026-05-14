package com.projectcollab.service;

import com.projectcollab.builder.TestDataBuilder;
import com.projectcollab.dto.CreateProjectRequest;
import com.projectcollab.entity.Project;
import com.projectcollab.exception.ProjectCollabException;
import com.projectcollab.repository.ProjectRepository;
import com.projectcollab.service.project.ProjectService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
@DisplayName("项目管理模块单元测试")
class ProjectManagementModuleTest {

    @Autowired
    private ProjectService projectService;

    @Autowired
    private ProjectRepository projectRepository;

    @Nested
    @DisplayName("项目创建与配置测试")
    class ProjectCreationTests {

        @Test
        @DisplayName("测试项目创建成功")
        void testCreateProject_Success() {
            CreateProjectRequest request = TestDataBuilder.buildCreateProjectRequest("测试项目", "development");

            Project result = projectService.createProject(request);

            assertNotNull(result, "创建的项目不应为null");
            assertNotNull(result.getProjectId(), "项目ID不应为null");
            assertEquals("测试项目", result.getProjectName(), "项目名称应匹配");
            assertEquals("development", result.getProjectType(), "项目类型应匹配");
            assertEquals(0, result.getProjectProgress(), "新项目进度应为0");
            assertNotNull(result.getCreatedAt(), "创建时间不应为null");
        }

        @Test
        @DisplayName("测试创建项目时设置默认状态为进行中")
        void testCreateProject_DefaultStatus() {
            CreateProjectRequest request = TestDataBuilder.buildCreateProjectRequest();

            Project result = projectService.createProject(request);

            assertEquals("in_progress", result.getProjectStatus(), "新项目默认状态应为in_progress");
        }

        @Test
        @DisplayName("测试创建不同类型的项目")
        void testCreateProject_DifferentTypes() {
            CreateProjectRequest devRequest = TestDataBuilder.buildCreateProjectRequest("开发项目", "development");
            CreateProjectRequest prodRequest = TestDataBuilder.buildCreateProjectRequest("运维项目", "operations");
            CreateProjectRequest designRequest = TestDataBuilder.buildCreateProjectRequest("设计项目", "design");

            Project devProject = projectService.createProject(devRequest);
            Project prodProject = projectService.createProject(prodRequest);
            Project designProject = projectService.createProject(designRequest);

            assertEquals("development", devProject.getProjectType());
            assertEquals("operations", prodProject.getProjectType());
            assertEquals("design", designProject.getProjectType());
        }

        @Test
        @DisplayName("测试项目信息存储正确性")
        void testProjectInfo_Persistence() {
            CreateProjectRequest request = TestDataBuilder.buildCreateProjectRequest("持久化测试项目", "testing");
            Project created = projectService.createProject(request);

            Optional<Project> found = projectService.getProjectById(created.getProjectId());

            assertTrue(found.isPresent(), "应能找到创建的项目");
            assertEquals("持久化测试项目", found.get().getProjectName());
            assertEquals("testing", found.get().getProjectType());
            assertEquals(created.getProjectStart(), found.get().getProjectStart());
            assertEquals(created.getProjectEnd(), found.get().getProjectEnd());
        }
    }

    @Nested
    @DisplayName("项目配置准确性测试")
    class ProjectConfigurationTests {

        private Project testProject;

        @BeforeEach
        void setUp() {
            testProject = TestDataBuilder.buildProject();
            projectRepository.save(testProject);
        }

        @Test
        @DisplayName("测试项目配置字段完整性")
        void testProjectConfiguration_AllFields() {
            assertNotNull(testProject.getProjectId());
            assertNotNull(testProject.getProjectName());
            assertNotNull(testProject.getProjectType());
            assertNotNull(testProject.getProjectStatus());
            assertNotNull(testProject.getProjectStart());
            assertNotNull(testProject.getProjectEnd());
            assertNotNull(testProject.getCreatedAt());
            assertEquals(0, testProject.getProjectProgress());
        }

        @Test
        @DisplayName("测试项目时间范围配置")
        void testProjectConfiguration_DateRange() {
            CreateProjectRequest request = TestDataBuilder.buildCreateProjectRequest();
            Project project = projectService.createProject(request);

            assertTrue(project.getProjectStart().isEqual(project.getProjectEnd()) || 
                       project.getProjectStart().isBefore(project.getProjectEnd()),
                       "项目开始日期应早于或等于结束日期");
        }
    }

    @Nested
    @DisplayName("项目状态生命周期测试")
    class ProjectStatusLifecycleTests {

        @Test
        @DisplayName("测试完整状态生命周期：规划中 -> 进行中 -> 已完成")
        void testProjectStatusLifecycle_FullCycle() {
            Project planningProject = TestDataBuilder.buildPlanningProject();
            projectRepository.save(planningProject);

            assertEquals("planning", planningProject.getProjectStatus(), "初始状态应为planning");

            Project inProgressProject = projectService.updateProjectStatus(
                    planningProject.getProjectId(), "in_progress");
            assertEquals("in_progress", inProgressProject.getProjectStatus(), "更新后状态应为in_progress");

            Project completedProject = projectService.updateProjectProgress(
                    inProgressProject.getProjectId(), 100);
            assertEquals("completed", completedProject.getProjectStatus(), "进度100%后状态应为completed");
            assertEquals(100, completedProject.getProjectProgress(), "进度应为100%");
        }

        @Test
        @DisplayName("测试状态：进行中 -> 已暂停 -> 恢复")
        void testProjectStatusLifecycle_PauseAndResume() {
            Project project = TestDataBuilder.buildInProgressProject();
            projectRepository.save(project);

            assertEquals("in_progress", project.getProjectStatus());

            Project pausedProject = projectService.updateProjectStatus(
                    project.getProjectId(), "paused");
            assertEquals("paused", pausedProject.getProjectStatus(), "暂停后状态应为paused");

            Project resumedProject = projectService.updateProjectStatus(
                    project.getProjectId(), "in_progress");
            assertEquals("in_progress", resumedProject.getProjectStatus(), "恢复后状态应为in_progress");
        }

        @Test
        @DisplayName("测试已完成项目的进度更新")
        void testProjectStatusLifecycle_CompletedProjectProgress() {
            Project completedProject = TestDataBuilder.buildCompletedProject();
            projectRepository.save(completedProject);

            assertEquals("completed", completedProject.getProjectStatus());
            assertEquals(100, completedProject.getProjectProgress());

            Project result = projectService.updateProjectProgress(
                    completedProject.getProjectId(), 50);

            assertEquals("completed", result.getProjectStatus(), "已完成项目状态不变");
            assertEquals(50, result.getProjectProgress(), "进度可更新");
        }

        @Test
        @DisplayName("测试验证已完成项目不允许创建任务")
        void testProjectStatusValidation_CompletedProject() {
            Project completedProject = TestDataBuilder.buildCompletedProject();
            projectRepository.save(completedProject);

            ProjectCollabException exception = assertThrows(
                    ProjectCollabException.class,
                    () -> projectService.validateProjectStatusForTaskCreation(completedProject)
            );

            assertEquals(400, exception.getCode());
            assertTrue(exception.getMessage().contains("项目已完成"));
        }

        @Test
        @DisplayName("测试验证已暂停项目不允许创建任务")
        void testProjectStatusValidation_PausedProject() {
            Project pausedProject = TestDataBuilder.buildPausedProject();
            projectRepository.save(pausedProject);

            ProjectCollabException exception = assertThrows(
                    ProjectCollabException.class,
                    () -> projectService.validateProjectStatusForTaskCreation(pausedProject)
            );

            assertEquals(400, exception.getCode());
            assertTrue(exception.getMessage().contains("项目已暂停"));
        }

        @Test
        @DisplayName("测试验证进行中项目允许创建任务")
        void testProjectStatusValidation_InProgressProject() {
            Project inProgressProject = TestDataBuilder.buildInProgressProject();
            projectRepository.save(inProgressProject);

            assertDoesNotThrow(() -> projectService.validateProjectStatusForTaskCreation(inProgressProject),
                    "进行中的项目应允许创建任务");
        }
    }

    @Nested
    @DisplayName("项目进度更新测试")
    class ProjectProgressTests {

        private Project testProject;

        @BeforeEach
        void setUp() {
            testProject = TestDataBuilder.buildInProgressProject();
            projectRepository.save(testProject);
        }

        @Test
        @DisplayName("测试进度更新为50%")
        void testProjectProgress_UpdateTo50() {
            Project result = projectService.updateProjectProgress(testProject.getProjectId(), 50);

            assertEquals(50, result.getProjectProgress(), "进度应为50%");
            assertEquals("in_progress", result.getProjectStatus(), "状态仍应为in_progress");
        }

        @Test
        @DisplayName("测试进度更新为100%触发状态变为已完成")
        void testProjectProgress_UpdateTo100() {
            Project result = projectService.updateProjectProgress(testProject.getProjectId(), 100);

            assertEquals(100, result.getProjectProgress(), "进度应为100%");
            assertEquals("completed", result.getProjectStatus(), "进度100%后状态应为completed");
        }

        @Test
        @DisplayName("测试进度更新为0%")
        void testProjectProgress_UpdateTo0() {
            Project result = projectService.updateProjectProgress(testProject.getProjectId(), 0);

            assertEquals(0, result.getProjectProgress(), "进度应为0%");
            assertEquals("in_progress", result.getProjectStatus());
        }
    }
}
