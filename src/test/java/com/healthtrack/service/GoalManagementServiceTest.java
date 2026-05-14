package com.healthtrack.service;

import com.healthtrack.entity.HealthGoal;
import com.healthtrack.entity.HealthHistory;
import com.healthtrack.repository.HealthGoalRepository;
import com.healthtrack.repository.HealthHistoryRepository;
import com.healthtrack.testbuilder.TestDataBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("目标管理模块单元测试")
class GoalManagementServiceTest {

    @Mock
    private HealthGoalRepository healthGoalRepository;

    @Mock
    private HealthHistoryRepository healthHistoryRepository;

    @InjectMocks
    private GoalManagementService goalManagementService;

    @InjectMocks
    private HistoryService historyService;

    @BeforeEach
    void setUp() {
    }

    @Nested
    @DisplayName("目标进度计算测试")
    class GoalProgressCalculationTests {

        @Test
        @DisplayName("下降型目标 - 从70降到65，目标65，进度100%")
        void testDecreaseTypeGoalProgress() {
            HealthGoal goal = new HealthGoal();
            goal.setGoalType("weight");
            goal.setStartValue(70.0);
            goal.setCurrentValue(67.5);
            goal.setTargetValue(65.0);
            goal.setStatus("in_progress");
            
            ArgumentCaptor<HealthGoal> goalCaptor = ArgumentCaptor.forClass(HealthGoal.class);
            when(healthGoalRepository.findByUserIdAndStatus(TestDataBuilder.getDefaultUserId(), "in_progress"))
                    .thenReturn(List.of(goal));
            when(healthGoalRepository.save(any(HealthGoal.class))).thenAnswer(invocation -> invocation.getArgument(0));
            when(healthHistoryRepository.save(any(HealthHistory.class))).thenAnswer(invocation -> invocation.getArgument(0));
            
            goalManagementService.checkGoals(TestDataBuilder.getDefaultUserId(), "weight", 65.0);
            
            verify(healthGoalRepository, times(1)).save(goalCaptor.capture());
            HealthGoal savedGoal = goalCaptor.getValue();
            assertEquals(100, savedGoal.getProgress());
            assertEquals("achieved", savedGoal.getStatus());
        }

        @Test
        @DisplayName("下降型目标 - 从70降到67.5，目标65，进度50%")
        void testDecreaseTypeGoalHalfProgress() {
            HealthGoal goal = new HealthGoal();
            goal.setUserId(TestDataBuilder.getDefaultUserId());
            goal.setGoalType("weight");
            goal.setStartValue(70.0);
            goal.setCurrentValue(70.0);
            goal.setTargetValue(65.0);
            goal.setStatus("in_progress");
            
            ArgumentCaptor<HealthGoal> goalCaptor = ArgumentCaptor.forClass(HealthGoal.class);
            when(healthGoalRepository.findByUserIdAndStatus(TestDataBuilder.getDefaultUserId(), "in_progress"))
                    .thenReturn(List.of(goal));
            when(healthGoalRepository.save(any(HealthGoal.class))).thenAnswer(invocation -> invocation.getArgument(0));
            when(healthHistoryRepository.save(any(HealthHistory.class))).thenAnswer(invocation -> invocation.getArgument(0));
            
            goalManagementService.checkGoals(TestDataBuilder.getDefaultUserId(), "weight", 67.5);
            
            verify(healthGoalRepository, times(1)).save(goalCaptor.capture());
            HealthGoal savedGoal = goalCaptor.getValue();
            assertEquals(50, savedGoal.getProgress());
            assertEquals("in_progress", savedGoal.getStatus());
        }

        @Test
        @DisplayName("上升型目标 - 从5000升到6500，目标8000，进度75%")
        void testIncreaseTypeGoalProgress() {
            HealthGoal goal = new HealthGoal();
            goal.setUserId(TestDataBuilder.getDefaultUserId());
            goal.setGoalType("steps");
            goal.setStartValue(5000.0);
            goal.setCurrentValue(5000.0);
            goal.setTargetValue(8000.0);
            goal.setStatus("in_progress");
            
            ArgumentCaptor<HealthGoal> goalCaptor = ArgumentCaptor.forClass(HealthGoal.class);
            when(healthGoalRepository.findByUserIdAndStatus(TestDataBuilder.getDefaultUserId(), "in_progress"))
                    .thenReturn(List.of(goal));
            when(healthGoalRepository.save(any(HealthGoal.class))).thenAnswer(invocation -> invocation.getArgument(0));
            when(healthHistoryRepository.save(any(HealthHistory.class))).thenAnswer(invocation -> invocation.getArgument(0));
            
            goalManagementService.checkGoals(TestDataBuilder.getDefaultUserId(), "steps", 7250.0);
            
            verify(healthGoalRepository, times(1)).save(goalCaptor.capture());
            HealthGoal savedGoal = goalCaptor.getValue();
            assertEquals(75, savedGoal.getProgress());
        }

        @Test
        @DisplayName("初始状态 - 进度0%")
        void testInitialProgress() {
            HealthGoal goal = new HealthGoal();
            goal.setUserId(TestDataBuilder.getDefaultUserId());
            goal.setGoalType("weight");
            goal.setStartValue(70.0);
            goal.setCurrentValue(70.0);
            goal.setTargetValue(65.0);
            goal.setStatus("in_progress");
            
            ArgumentCaptor<HealthGoal> goalCaptor = ArgumentCaptor.forClass(HealthGoal.class);
            when(healthGoalRepository.findByUserIdAndStatus(TestDataBuilder.getDefaultUserId(), "in_progress"))
                    .thenReturn(List.of(goal));
            when(healthGoalRepository.save(any(HealthGoal.class))).thenAnswer(invocation -> invocation.getArgument(0));
            when(healthHistoryRepository.save(any(HealthHistory.class))).thenAnswer(invocation -> invocation.getArgument(0));
            
            goalManagementService.checkGoals(TestDataBuilder.getDefaultUserId(), "weight", 70.0);
            
            verify(healthGoalRepository, times(1)).save(goalCaptor.capture());
            HealthGoal savedGoal = goalCaptor.getValue();
            assertEquals(0, savedGoal.getProgress());
        }

        @Test
        @DisplayName("超过目标 - 进度仍为100%（边界值保护）")
        void testProgressCapAt100() {
            HealthGoal goal = new HealthGoal();
            goal.setUserId(TestDataBuilder.getDefaultUserId());
            goal.setGoalType("weight");
            goal.setStartValue(70.0);
            goal.setCurrentValue(70.0);
            goal.setTargetValue(65.0);
            goal.setStatus("in_progress");
            
            ArgumentCaptor<HealthGoal> goalCaptor = ArgumentCaptor.forClass(HealthGoal.class);
            when(healthGoalRepository.findByUserIdAndStatus(TestDataBuilder.getDefaultUserId(), "in_progress"))
                    .thenReturn(List.of(goal));
            when(healthGoalRepository.save(any(HealthGoal.class))).thenAnswer(invocation -> invocation.getArgument(0));
            when(healthHistoryRepository.save(any(HealthHistory.class))).thenAnswer(invocation -> invocation.getArgument(0));
            
            goalManagementService.checkGoals(TestDataBuilder.getDefaultUserId(), "weight", 60.0);
            
            verify(healthGoalRepository, times(1)).save(goalCaptor.capture());
            HealthGoal savedGoal = goalCaptor.getValue();
            assertEquals(100, savedGoal.getProgress());
        }

        @Test
        @DisplayName("起点和目标相同 - 直接100%进度")
        void testStartEqualsTarget() {
            HealthGoal goal = new HealthGoal();
            goal.setUserId(TestDataBuilder.getDefaultUserId());
            goal.setGoalType("heart_rate");
            goal.setStartValue(75.0);
            goal.setCurrentValue(75.0);
            goal.setTargetValue(75.0);
            goal.setStatus("in_progress");
            
            ArgumentCaptor<HealthGoal> goalCaptor = ArgumentCaptor.forClass(HealthGoal.class);
            when(healthGoalRepository.findByUserIdAndStatus(TestDataBuilder.getDefaultUserId(), "in_progress"))
                    .thenReturn(List.of(goal));
            when(healthGoalRepository.save(any(HealthGoal.class))).thenAnswer(invocation -> invocation.getArgument(0));
            when(healthHistoryRepository.save(any(HealthHistory.class))).thenAnswer(invocation -> invocation.getArgument(0));
            
            goalManagementService.checkGoals(TestDataBuilder.getDefaultUserId(), "heart_rate", 75.0);
            
            verify(healthGoalRepository, times(1)).save(goalCaptor.capture());
            HealthGoal savedGoal = goalCaptor.getValue();
            assertEquals(100, savedGoal.getProgress());
            assertEquals("achieved", savedGoal.getStatus());
        }

        @Test
        @DisplayName("逆向进展 - 进度保持0%（边界值保护）")
        void testNegativeProgressProtected() {
            HealthGoal goal = new HealthGoal();
            goal.setUserId(TestDataBuilder.getDefaultUserId());
            goal.setGoalType("weight");
            goal.setStartValue(70.0);
            goal.setCurrentValue(70.0);
            goal.setTargetValue(65.0);
            goal.setStatus("in_progress");
            
            ArgumentCaptor<HealthGoal> goalCaptor = ArgumentCaptor.forClass(HealthGoal.class);
            when(healthGoalRepository.findByUserIdAndStatus(TestDataBuilder.getDefaultUserId(), "in_progress"))
                    .thenReturn(List.of(goal));
            when(healthGoalRepository.save(any(HealthGoal.class))).thenAnswer(invocation -> invocation.getArgument(0));
            when(healthHistoryRepository.save(any(HealthHistory.class))).thenAnswer(invocation -> invocation.getArgument(0));
            
            goalManagementService.checkGoals(TestDataBuilder.getDefaultUserId(), "weight", 75.0);
            
            verify(healthGoalRepository, times(1)).save(goalCaptor.capture());
            HealthGoal savedGoal = goalCaptor.getValue();
            assertEquals(0, savedGoal.getProgress());
        }
    }

    @Nested
    @DisplayName("目标达成判断测试")
    class GoalAchievementTests {

        @Test
        @DisplayName("下降型目标 - 达到目标值时达成")
        void testDecreaseGoalAchieved() {
            HealthGoal goal = new HealthGoal();
            goal.setUserId(TestDataBuilder.getDefaultUserId());
            goal.setGoalType("weight");
            goal.setStartValue(70.0);
            goal.setCurrentValue(70.0);
            goal.setTargetValue(65.0);
            goal.setStatus("in_progress");
            
            ArgumentCaptor<HealthGoal> goalCaptor = ArgumentCaptor.forClass(HealthGoal.class);
            when(healthGoalRepository.findByUserIdAndStatus(TestDataBuilder.getDefaultUserId(), "in_progress"))
                    .thenReturn(List.of(goal));
            when(healthGoalRepository.save(any(HealthGoal.class))).thenAnswer(invocation -> invocation.getArgument(0));
            when(healthHistoryRepository.save(any(HealthHistory.class))).thenAnswer(invocation -> invocation.getArgument(0));
            
            goalManagementService.checkGoals(TestDataBuilder.getDefaultUserId(), "weight", 65.0);
            
            verify(healthGoalRepository, times(1)).save(goalCaptor.capture());
            assertEquals("achieved", goalCaptor.getValue().getStatus());
        }

        @Test
        @DisplayName("下降型目标 - 超过目标值时也达成")
        void testDecreaseGoalExceeded() {
            HealthGoal goal = new HealthGoal();
            goal.setUserId(TestDataBuilder.getDefaultUserId());
            goal.setGoalType("weight");
            goal.setStartValue(70.0);
            goal.setCurrentValue(70.0);
            goal.setTargetValue(65.0);
            goal.setStatus("in_progress");
            
            ArgumentCaptor<HealthGoal> goalCaptor = ArgumentCaptor.forClass(HealthGoal.class);
            when(healthGoalRepository.findByUserIdAndStatus(TestDataBuilder.getDefaultUserId(), "in_progress"))
                    .thenReturn(List.of(goal));
            when(healthGoalRepository.save(any(HealthGoal.class))).thenAnswer(invocation -> invocation.getArgument(0));
            when(healthHistoryRepository.save(any(HealthHistory.class))).thenAnswer(invocation -> invocation.getArgument(0));
            
            goalManagementService.checkGoals(TestDataBuilder.getDefaultUserId(), "weight", 63.0);
            
            verify(healthGoalRepository, times(1)).save(goalCaptor.capture());
            assertEquals("achieved", goalCaptor.getValue().getStatus());
        }

        @Test
        @DisplayName("上升型目标 - 达到目标值时达成")
        void testIncreaseGoalAchieved() {
            HealthGoal goal = new HealthGoal();
            goal.setUserId(TestDataBuilder.getDefaultUserId());
            goal.setGoalType("steps");
            goal.setStartValue(5000.0);
            goal.setCurrentValue(5000.0);
            goal.setTargetValue(8000.0);
            goal.setStatus("in_progress");
            
            ArgumentCaptor<HealthGoal> goalCaptor = ArgumentCaptor.forClass(HealthGoal.class);
            when(healthGoalRepository.findByUserIdAndStatus(TestDataBuilder.getDefaultUserId(), "in_progress"))
                    .thenReturn(List.of(goal));
            when(healthGoalRepository.save(any(HealthGoal.class))).thenAnswer(invocation -> invocation.getArgument(0));
            when(healthHistoryRepository.save(any(HealthHistory.class))).thenAnswer(invocation -> invocation.getArgument(0));
            
            goalManagementService.checkGoals(TestDataBuilder.getDefaultUserId(), "steps", 8000.0);
            
            verify(healthGoalRepository, times(1)).save(goalCaptor.capture());
            assertEquals("achieved", goalCaptor.getValue().getStatus());
        }

        @Test
        @DisplayName("上升型目标 - 超过目标值时也达成")
        void testIncreaseGoalExceeded() {
            HealthGoal goal = new HealthGoal();
            goal.setUserId(TestDataBuilder.getDefaultUserId());
            goal.setGoalType("steps");
            goal.setStartValue(5000.0);
            goal.setCurrentValue(5000.0);
            goal.setTargetValue(8000.0);
            goal.setStatus("in_progress");
            
            ArgumentCaptor<HealthGoal> goalCaptor = ArgumentCaptor.forClass(HealthGoal.class);
            when(healthGoalRepository.findByUserIdAndStatus(TestDataBuilder.getDefaultUserId(), "in_progress"))
                    .thenReturn(List.of(goal));
            when(healthGoalRepository.save(any(HealthGoal.class))).thenAnswer(invocation -> invocation.getArgument(0));
            when(healthHistoryRepository.save(any(HealthHistory.class))).thenAnswer(invocation -> invocation.getArgument(0));
            
            goalManagementService.checkGoals(TestDataBuilder.getDefaultUserId(), "steps", 10000.0);
            
            verify(healthGoalRepository, times(1)).save(goalCaptor.capture());
            assertEquals("achieved", goalCaptor.getValue().getStatus());
        }

        @Test
        @DisplayName("未达到目标 - 保持in_progress状态")
        void testGoalNotAchieved() {
            HealthGoal goal = new HealthGoal();
            goal.setUserId(TestDataBuilder.getDefaultUserId());
            goal.setGoalType("weight");
            goal.setStartValue(70.0);
            goal.setCurrentValue(70.0);
            goal.setTargetValue(65.0);
            goal.setStatus("in_progress");
            
            ArgumentCaptor<HealthGoal> goalCaptor = ArgumentCaptor.forClass(HealthGoal.class);
            when(healthGoalRepository.findByUserIdAndStatus(TestDataBuilder.getDefaultUserId(), "in_progress"))
                    .thenReturn(List.of(goal));
            when(healthGoalRepository.save(any(HealthGoal.class))).thenAnswer(invocation -> invocation.getArgument(0));
            when(healthHistoryRepository.save(any(HealthHistory.class))).thenAnswer(invocation -> invocation.getArgument(0));
            
            goalManagementService.checkGoals(TestDataBuilder.getDefaultUserId(), "weight", 68.0);
            
            verify(healthGoalRepository, times(1)).save(goalCaptor.capture());
            assertEquals("in_progress", goalCaptor.getValue().getStatus());
        }

        @Test
        @DisplayName("已达成目标 - 不再更新状态")
        void testAlreadyAchievedGoal() {
            HealthGoal goal = new HealthGoal();
            goal.setUserId(TestDataBuilder.getDefaultUserId());
            goal.setGoalType("weight");
            goal.setStartValue(70.0);
            goal.setCurrentValue(65.0);
            goal.setTargetValue(65.0);
            goal.setStatus("achieved");
            
            when(healthGoalRepository.findByUserIdAndStatus(TestDataBuilder.getDefaultUserId(), "in_progress"))
                    .thenReturn(List.of());
            
            goalManagementService.checkGoals(TestDataBuilder.getDefaultUserId(), "weight", 64.0);
            
            verify(healthGoalRepository, never()).save(any(HealthGoal.class));
        }

        @Test
        @DisplayName("不同类型的数据 - 不影响其他目标")
        void testDifferentDataTypeNoImpact() {
            HealthGoal weightGoal = new HealthGoal();
            weightGoal.setUserId(TestDataBuilder.getDefaultUserId());
            weightGoal.setGoalType("weight");
            weightGoal.setStartValue(70.0);
            weightGoal.setCurrentValue(70.0);
            weightGoal.setTargetValue(65.0);
            weightGoal.setStatus("in_progress");
            
            when(healthGoalRepository.findByUserIdAndStatus(TestDataBuilder.getDefaultUserId(), "in_progress"))
                    .thenReturn(List.of(weightGoal));
            when(healthHistoryRepository.save(any(HealthHistory.class))).thenAnswer(invocation -> invocation.getArgument(0));
            
            goalManagementService.checkGoals(TestDataBuilder.getDefaultUserId(), "heart_rate", 75.0);
            
            verify(healthGoalRepository, never()).save(any(HealthGoal.class));
        }
    }

    @Nested
    @DisplayName("目标达成后成就记录测试")
    class AchievementRecordTests {

        @Test
        @DisplayName("目标达成 - 记录历史记录")
        void testGoalAchievementHistoryRecorded() {
            HealthGoal goal = new HealthGoal();
            goal.setUserId(TestDataBuilder.getDefaultUserId());
            goal.setGoalType("weight");
            goal.setStartValue(70.0);
            goal.setCurrentValue(70.0);
            goal.setTargetValue(65.0);
            goal.setStatus("in_progress");
            
            when(healthGoalRepository.findByUserIdAndStatus(TestDataBuilder.getDefaultUserId(), "in_progress"))
                    .thenReturn(List.of(goal));
            when(healthGoalRepository.save(any(HealthGoal.class))).thenAnswer(invocation -> invocation.getArgument(0));
            when(healthHistoryRepository.save(any(HealthHistory.class))).thenAnswer(invocation -> invocation.getArgument(0));
            
            goalManagementService.checkGoals(TestDataBuilder.getDefaultUserId(), "weight", 65.0);
            
            ArgumentCaptor<HealthHistory> historyCaptor = ArgumentCaptor.forClass(HealthHistory.class);
            verify(healthHistoryRepository, times(1)).save(historyCaptor.capture());
            
            HealthHistory history = historyCaptor.getValue();
            assertEquals(TestDataBuilder.getDefaultUserId(), history.getUserId());
            assertEquals("weight", history.getDataType());
            assertEquals("GOAL_ACHIEVED", history.getActionType());
            assertEquals(65.0, history.getNewValue());
        }

        @Test
        @DisplayName("目标达成描述包含目标类型")
        void testGoalAchievementDescription() {
            HealthGoal goal = new HealthGoal();
            goal.setUserId(TestDataBuilder.getDefaultUserId());
            goal.setGoalType("steps");
            goal.setStartValue(5000.0);
            goal.setCurrentValue(5000.0);
            goal.setTargetValue(8000.0);
            goal.setStatus("in_progress");
            
            when(healthGoalRepository.findByUserIdAndStatus(TestDataBuilder.getDefaultUserId(), "in_progress"))
                    .thenReturn(List.of(goal));
            when(healthGoalRepository.save(any(HealthGoal.class))).thenAnswer(invocation -> invocation.getArgument(0));
            when(healthHistoryRepository.save(any(HealthHistory.class))).thenAnswer(invocation -> invocation.getArgument(0));
            
            goalManagementService.checkGoals(TestDataBuilder.getDefaultUserId(), "steps", 8000.0);
            
            ArgumentCaptor<HealthHistory> historyCaptor = ArgumentCaptor.forClass(HealthHistory.class);
            verify(healthHistoryRepository, times(1)).save(historyCaptor.capture());
            
            HealthHistory history = historyCaptor.getValue();
            assertNotNull(history.getDescription());
            assertTrue(history.getDescription().contains("steps"));
        }

        @Test
        @DisplayName("未达成目标 - 不记录成就历史")
        void testNoAchievementHistoryWhenNotAchieved() {
            HealthGoal goal = new HealthGoal();
            goal.setUserId(TestDataBuilder.getDefaultUserId());
            goal.setGoalType("weight");
            goal.setStartValue(70.0);
            goal.setCurrentValue(70.0);
            goal.setTargetValue(65.0);
            goal.setStatus("in_progress");
            
            when(healthGoalRepository.findByUserIdAndStatus(TestDataBuilder.getDefaultUserId(), "in_progress"))
                    .thenReturn(List.of(goal));
            when(healthGoalRepository.save(any(HealthGoal.class))).thenAnswer(invocation -> invocation.getArgument(0));
            
            goalManagementService.checkGoals(TestDataBuilder.getDefaultUserId(), "weight", 68.0);
            
            verify(healthHistoryRepository, never()).save(any(HealthHistory.class));
        }

        @Test
        @DisplayName("多个目标同时达成 - 分别记录成就")
        void testMultipleGoalsAchieved() {
            HealthGoal weightGoal = new HealthGoal();
            weightGoal.setUserId(TestDataBuilder.getDefaultUserId());
            weightGoal.setGoalType("weight");
            weightGoal.setStartValue(70.0);
            weightGoal.setCurrentValue(70.0);
            weightGoal.setTargetValue(65.0);
            weightGoal.setStatus("in_progress");
            
            HealthGoal stepsGoal = new HealthGoal();
            stepsGoal.setUserId(TestDataBuilder.getDefaultUserId());
            stepsGoal.setGoalType("steps");
            stepsGoal.setStartValue(5000.0);
            stepsGoal.setCurrentValue(5000.0);
            stepsGoal.setTargetValue(8000.0);
            stepsGoal.setStatus("in_progress");
            
            when(healthGoalRepository.findByUserIdAndStatus(TestDataBuilder.getDefaultUserId(), "in_progress"))
                    .thenReturn(Arrays.asList(weightGoal, stepsGoal));
            when(healthGoalRepository.save(any(HealthGoal.class))).thenAnswer(invocation -> invocation.getArgument(0));
            when(healthHistoryRepository.save(any(HealthHistory.class))).thenAnswer(invocation -> invocation.getArgument(0));
            
            goalManagementService.checkGoals(TestDataBuilder.getDefaultUserId(), "weight", 65.0);
            
            verify(healthGoalRepository, times(2)).save(any(HealthGoal.class));
            verify(healthHistoryRepository, times(1)).save(any(HealthHistory.class));
        }
    }

    @Nested
    @DisplayName("目标CRUD操作测试")
    class GoalCrudTests {

        @Test
        @DisplayName("创建目标 - 成功保存")
        void testCreateGoal() {
            HealthGoal goal = TestDataBuilder.buildWeightGoal(TestDataBuilder.getDefaultUserId());
            when(healthGoalRepository.save(any(HealthGoal.class))).thenAnswer(invocation -> invocation.getArgument(0));
            
            HealthGoal created = goalManagementService.createGoal(goal);
            
            assertNotNull(created);
            assertNotNull(created.getGoalId());
            assertTrue(created.getGoalId().startsWith("goal_"));
            assertEquals("in_progress", created.getStatus());
            assertEquals(0, created.getProgress());
            assertEquals(created.getStartValue(), created.getCurrentValue());
            verify(healthGoalRepository, times(1)).save(any(HealthGoal.class));
        }

        @Test
        @DisplayName("创建目标 - ID唯一性")
        void testCreateGoalUniqueId() {
            HealthGoal goal1 = TestDataBuilder.buildWeightGoal(TestDataBuilder.getDefaultUserId());
            HealthGoal goal2 = TestDataBuilder.buildWeightGoal(TestDataBuilder.getDefaultUserId());
            
            when(healthGoalRepository.save(any(HealthGoal.class))).thenAnswer(invocation -> invocation.getArgument(0));
            
            HealthGoal created1 = goalManagementService.createGoal(goal1);
            HealthGoal created2 = goalManagementService.createGoal(goal2);
            
            assertNotEquals(created1.getGoalId(), created2.getGoalId());
        }

        @Test
        @DisplayName("查询用户目标列表")
        void testGetUserGoals() {
            List<HealthGoal> expectedGoals = Arrays.asList(
                    TestDataBuilder.buildWeightGoal(TestDataBuilder.getDefaultUserId()),
                    TestDataBuilder.buildWeightGoal("user_002")
            );
            
            when(healthGoalRepository.findByUserId(TestDataBuilder.getDefaultUserId()))
                    .thenReturn(expectedGoals);
            
            List<HealthGoal> actualGoals = goalManagementService.getUserGoals(TestDataBuilder.getDefaultUserId());
            
            assertNotNull(actualGoals);
            assertEquals(expectedGoals.size(), actualGoals.size());
        }

        @Test
        @DisplayName("查询单个目标 - 存在")
        void testGetGoalByIdExists() {
            HealthGoal expectedGoal = TestDataBuilder.buildWeightGoal(TestDataBuilder.getDefaultUserId());
            expectedGoal.setGoalId("goal_test_001");
            
            when(healthGoalRepository.findById("goal_test_001"))
                    .thenReturn(Optional.of(expectedGoal));
            
            Optional<HealthGoal> actualGoal = goalManagementService.getGoalById("goal_test_001");
            
            assertTrue(actualGoal.isPresent());
            assertEquals("goal_test_001", actualGoal.get().getGoalId());
        }

        @Test
        @DisplayName("查询单个目标 - 不存在")
        void testGetGoalByIdNotExists() {
            when(healthGoalRepository.findById("non_existent"))
                    .thenReturn(Optional.empty());
            
            Optional<HealthGoal> actualGoal = goalManagementService.getGoalById("non_existent");
            
            assertFalse(actualGoal.isPresent());
        }

        @Test
        @DisplayName("更新目标 - 成功")
        void testUpdateGoal() {
            HealthGoal existingGoal = TestDataBuilder.buildWeightGoal(TestDataBuilder.getDefaultUserId());
            existingGoal.setGoalId("goal_test_001");
            existingGoal.setTargetValue(65.0);
            
            HealthGoal updatedGoal = new HealthGoal();
            updatedGoal.setTargetValue(60.0);
            updatedGoal.setDeadline(LocalDate.now().plusMonths(2));
            updatedGoal.setDescription("更新后的减重目标");
            
            when(healthGoalRepository.findById("goal_test_001"))
                    .thenReturn(Optional.of(existingGoal));
            when(healthGoalRepository.save(any(HealthGoal.class))).thenAnswer(invocation -> invocation.getArgument(0));
            
            HealthGoal result = goalManagementService.updateGoal("goal_test_001", updatedGoal);
            
            assertEquals(60.0, result.getTargetValue());
            assertEquals(LocalDate.now().plusMonths(2), result.getDeadline());
            assertEquals("更新后的减重目标", result.getDescription());
        }

        @Test
        @DisplayName("更新目标 - 不存在抛出异常")
        void testUpdateGoalNotExists() {
            HealthGoal updatedGoal = new HealthGoal();
            updatedGoal.setTargetValue(60.0);
            
            when(healthGoalRepository.findById("non_existent"))
                    .thenReturn(Optional.empty());
            
            assertThrows(IllegalArgumentException.class,
                    () -> goalManagementService.updateGoal("non_existent", updatedGoal));
        }

        @Test
        @DisplayName("删除目标 - 成功")
        void testDeleteGoal() {
            doNothing().when(healthGoalRepository).deleteById("goal_test_001");
            
            goalManagementService.deleteGoal("goal_test_001");
            
            verify(healthGoalRepository, times(1)).deleteById("goal_test_001");
        }
    }

    @Nested
    @DisplayName("高并发目标更新测试")
    class HighConcurrencyTests {

        @Test
        @DisplayName("并发目标更新 - 线程安全")
        void testConcurrentGoalUpdates() throws InterruptedException {
            int threadCount = 20;
            ExecutorService executor = Executors.newFixedThreadPool(5);
            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch endLatch = new CountDownLatch(threadCount);
            AtomicInteger saveCount = new AtomicInteger(0);
            
            HealthGoal goal = TestDataBuilder.buildWeightGoal(TestDataBuilder.getDefaultUserId());
            goal.setUserId(TestDataBuilder.getDefaultUserId());
            goal.setCurrentValue(70.0);
            
            when(healthGoalRepository.findByUserIdAndStatus(TestDataBuilder.getDefaultUserId(), "in_progress"))
                    .thenReturn(List.of(goal));
            when(healthGoalRepository.save(any(HealthGoal.class))).thenAnswer(invocation -> {
                saveCount.incrementAndGet();
                return invocation.getArgument(0);
            });
            when(healthHistoryRepository.save(any(HealthHistory.class))).thenAnswer(invocation -> invocation.getArgument(0));
            
            for (int i = 0; i < threadCount; i++) {
                final double value = 70.0 - (i * 0.25);
                executor.submit(() -> {
                    try {
                        startLatch.await();
                        goalManagementService.checkGoals(TestDataBuilder.getDefaultUserId(), "weight", value);
                    } catch (Exception e) {
                        // 记录异常
                    } finally {
                        endLatch.countDown();
                    }
                });
            }
            
            startLatch.countDown();
            boolean completed = endLatch.await(30, TimeUnit.SECONDS);
            executor.shutdown();
            executor.awaitTermination(10, TimeUnit.SECONDS);
            
            assertTrue(completed);
            assertEquals(threadCount, saveCount.get());
        }
    }
}
