package com.fitnesscenter.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fitnesscenter.builder.TestDataBuilder;
import com.fitnesscenter.dto.PlanRequest;
import com.fitnesscenter.dto.PlanResponse;
import com.fitnesscenter.model.*;
import com.fitnesscenter.repository.HistoryRepository;
import com.fitnesscenter.repository.PlanRepository;
import com.fitnesscenter.repository.StatisticRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Arrays;
import java.util.Collections;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("计划模块测试")
class PlanServiceTest {

    @Mock
    private PlanRepository planRepository;

    @Mock
    private MemberService memberService;

    @Mock
    private HistoryRepository historyRepository;

    @Mock
    private StatisticRepository statisticRepository;

    @Spy
    private ObjectMapper objectMapper;

    @InjectMocks
    private PlanService planService;

    private Member vipMember;
    private Member regularMember;
    private Plan activePlan;
    private Plan completedPlan;

    @BeforeEach
    void setUp() {
        vipMember = TestDataBuilder.buildVipMember();
        regularMember = TestDataBuilder.buildRegularMember();
        activePlan = TestDataBuilder.buildWeightLossPlan(vipMember.getMemberId());
        completedPlan = TestDataBuilder.buildCompletedPlan(vipMember.getMemberId());
    }

    @Test
    @DisplayName("测试创建新计划")
    void testCreateNewPlan() {
        PlanRequest request = TestDataBuilder.buildWeightLossPlanRequest(vipMember.getMemberId());
        Plan savedPlan = TestDataBuilder.buildWeightLossPlan(vipMember.getMemberId());

        when(memberService.getMemberById(vipMember.getMemberId())).thenReturn(vipMember);
        when(planRepository.findByMemberId(vipMember.getMemberId())).thenReturn(Optional.empty());
        when(planRepository.save(any(Plan.class))).thenReturn(savedPlan);
        when(statisticRepository.findByStatMonth(anyString())).thenReturn(Optional.empty());
        when(statisticRepository.save(any(Statistic.class))).thenReturn(new Statistic());
        when(historyRepository.save(any(History.class))).thenReturn(new History());

        Plan createdPlan = planService.createPlan(request);

        assertNotNull(createdPlan, "创建的计划不应该为空");
        assertEquals("weight_loss", createdPlan.getPlanType(), "计划类型应该正确");
        assertEquals("in_progress", createdPlan.getPlanStatus(), "计划状态应该为进行中");
        assertEquals(0, createdPlan.getPlanProgress(), "初始进度应该为0");
        verify(planRepository).save(any(Plan.class));
    }

    @Test
    @DisplayName("测试已有进行中计划时拒绝创建")
    void testCreatePlanWhenHasActivePlan() {
        PlanRequest request = TestDataBuilder.buildWeightLossPlanRequest(vipMember.getMemberId());

        when(memberService.getMemberById(vipMember.getMemberId())).thenReturn(vipMember);
        when(planRepository.findByMemberId(vipMember.getMemberId())).thenReturn(Optional.of(activePlan));

        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> planService.createPlan(request),
                "已有进行中计划时应该拒绝创建");
        assertTrue(exception.getMessage().contains("已有进行中的健身计划"));
    }

    @Test
    @DisplayName("测试会员不存在时拒绝创建计划")
    void testCreatePlanWhenMemberNotExists() {
        PlanRequest request = TestDataBuilder.buildWeightLossPlanRequest("NON_EXISTENT_MEMBER");

        doThrow(new IllegalArgumentException("会员不存在"))
                .when(memberService).getMemberById("NON_EXISTENT_MEMBER");

        assertThrows(IllegalArgumentException.class,
                () -> planService.createPlan(request),
                "会员不存在时应该抛出异常");
    }

    @Test
    @DisplayName("测试查询会员计划 - 有计划")
    void testQueryPlanWhenHasPlan() {
        when(planRepository.findByMemberId(vipMember.getMemberId())).thenReturn(Optional.of(activePlan));

        PlanResponse response = planService.queryPlan(vipMember.getMemberId());

        assertNotNull(response, "响应不应该为空");
        assertNotNull(response.getPlan(), "计划信息不应该为空");
        assertEquals(50, response.getPlan().getProgress(), "进度应该为50");
        assertEquals("in_progress", response.getPlan().getStatus(), "状态应该为进行中");
        assertEquals(activePlan.getPlanId(), response.getPlan().getPlanId(), "计划ID应该正确");
    }

    @Test
    @DisplayName("测试查询会员计划 - 无计划")
    void testQueryPlanWhenNoPlan() {
        when(planRepository.findByMemberId(regularMember.getMemberId())).thenReturn(Optional.empty());

        PlanResponse response = planService.queryPlan(regularMember.getMemberId());

        assertNotNull(response, "响应不应该为空");
        assertNull(response.getPlan(), "计划信息应该为空");
    }

    @Test
    @DisplayName("测试通过会员ID获取计划")
    void testGetPlanByMemberId() {
        when(planRepository.findByMemberId(vipMember.getMemberId())).thenReturn(Optional.of(activePlan));

        Plan plan = planService.getPlanByMemberId(vipMember.getMemberId());

        assertNotNull(plan, "计划不应该为空");
        assertEquals(activePlan.getPlanId(), plan.getPlanId(), "计划ID应该正确");
    }

    @Test
    @DisplayName("测试通过会员ID获取计划 - 无计划")
    void testGetPlanByMemberIdWhenNoPlan() {
        when(planRepository.findByMemberId(regularMember.getMemberId())).thenReturn(Optional.empty());

        Plan plan = planService.getPlanByMemberId(regularMember.getMemberId());

        assertNull(plan, "无计划时应该返回null");
    }

    @Test
    @DisplayName("测试通过计划ID获取计划")
    void testGetPlanById() {
        when(planRepository.findByPlanId(activePlan.getPlanId())).thenReturn(Optional.of(activePlan));

        Plan plan = planService.getPlanById(activePlan.getPlanId());

        assertNotNull(plan, "计划不应该为空");
        assertEquals(activePlan.getPlanId(), plan.getPlanId(), "计划ID应该正确");
    }

    @Test
    @DisplayName("测试通过计划ID获取计划 - 不存在")
    void testGetPlanByIdWhenNotExists() {
        when(planRepository.findByPlanId("NON_EXISTENT_PLAN")).thenReturn(Optional.empty());

        assertThrows(IllegalArgumentException.class,
                () -> planService.getPlanById("NON_EXISTENT_PLAN"),
                "不存在的计划应该抛出异常");
    }

    @Test
    @DisplayName("测试获取所有计划")
    void testGetAllPlans() {
        Plan plan1 = TestDataBuilder.buildWeightLossPlan(vipMember.getMemberId());
        Plan plan2 = TestDataBuilder.buildNewPlan(regularMember.getMemberId());
        when(planRepository.findAll()).thenReturn(Arrays.asList(plan1, plan2));

        java.util.List<Plan> plans = planService.getAllPlans();

        assertEquals(2, plans.size(), "应该返回2个计划");
    }

    @Test
    @DisplayName("测试获取所有计划 - 空列表")
    void testGetAllPlansWhenEmpty() {
        when(planRepository.findAll()).thenReturn(Collections.emptyList());

        java.util.List<Plan> plans = planService.getAllPlans();

        assertTrue(plans.isEmpty(), "应该返回空列表");
    }

    @Test
    @DisplayName("测试更新计划进度")
    void testUpdatePlanProgress() {
        Plan plan = TestDataBuilder.buildWeightLossPlan(vipMember.getMemberId());
        plan.setPlanProgress(50);
        when(planRepository.findByMemberId(vipMember.getMemberId())).thenReturn(Optional.of(plan));
        when(planRepository.save(any(Plan.class))).thenAnswer(invocation -> invocation.getArgument(0));

        planService.updatePlanProgress(vipMember.getMemberId(), 1000);

        verify(planRepository).save(any(Plan.class));
    }

    @Test
    @DisplayName("测试计划进度达到100%时状态变为已完成")
    void testPlanStatusChangesToCompletedWhenProgressReaches100() {
        Plan plan = TestDataBuilder.buildWeightLossPlan(vipMember.getMemberId());
        plan.setPlanProgress(99);
        when(planRepository.findByMemberId(vipMember.getMemberId())).thenReturn(Optional.of(plan));
        when(planRepository.save(any(Plan.class))).thenAnswer(invocation -> invocation.getArgument(0));

        planService.updatePlanProgress(vipMember.getMemberId(), 2000);

        verify(planRepository).save(any(Plan.class));
        verify(historyRepository).save(any(History.class));
    }

    @Test
    @DisplayName("测试无计划时更新进度不做任何操作")
    void testUpdatePlanProgressWhenNoPlan() {
        when(planRepository.findByMemberId(regularMember.getMemberId())).thenReturn(Optional.empty());

        planService.updatePlanProgress(regularMember.getMemberId(), 1000);

        verify(planRepository, never()).save(any(Plan.class));
    }

    @Test
    @DisplayName("测试已完成计划更新进度不做任何操作")
    void testUpdatePlanProgressWhenPlanCompleted() {
        Plan completedPlan = TestDataBuilder.buildCompletedPlan(vipMember.getMemberId());
        when(planRepository.findByMemberId(vipMember.getMemberId())).thenReturn(Optional.of(completedPlan));

        planService.updatePlanProgress(vipMember.getMemberId(), 1000);

        verify(planRepository, never()).save(any(Plan.class));
    }

    @Test
    @DisplayName("测试更新计划状态")
    void testUpdatePlanStatus() {
        Plan plan = TestDataBuilder.buildWeightLossPlan(vipMember.getMemberId());
        when(planRepository.findByPlanId(plan.getPlanId())).thenReturn(Optional.of(plan));
        when(planRepository.save(any(Plan.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Plan updatedPlan = planService.updatePlanStatus(plan.getPlanId(), "paused");

        assertEquals("paused", updatedPlan.getPlanStatus(), "状态应该被更新");
        verify(planRepository).save(any(Plan.class));
    }

    @Test
    @DisplayName("测试更新不存在计划的状态")
    void testUpdatePlanStatusWhenNotExists() {
        when(planRepository.findByPlanId("NON_EXISTENT_PLAN")).thenReturn(Optional.empty());

        assertThrows(IllegalArgumentException.class,
                () -> planService.updatePlanStatus("NON_EXISTENT_PLAN", "paused"),
                "不存在的计划应该抛出异常");
    }

    @Test
    @DisplayName("测试计划历史记录")
    void testPlanHistoryIsRecorded() {
        PlanRequest request = TestDataBuilder.buildWeightLossPlanRequest(vipMember.getMemberId());
        Plan savedPlan = TestDataBuilder.buildWeightLossPlan(vipMember.getMemberId());

        when(memberService.getMemberById(vipMember.getMemberId())).thenReturn(vipMember);
        when(planRepository.findByMemberId(vipMember.getMemberId())).thenReturn(Optional.empty());
        when(planRepository.save(any(Plan.class))).thenReturn(savedPlan);
        when(statisticRepository.findByStatMonth(anyString())).thenReturn(Optional.empty());
        when(statisticRepository.save(any(Statistic.class))).thenReturn(new Statistic());

        planService.createPlan(request);

        verify(historyRepository).save(any(History.class));
    }

    @Test
    @DisplayName("测试统计数据更新")
    void testPlanStatisticsAreUpdated() {
        PlanRequest request = TestDataBuilder.buildWeightLossPlanRequest(vipMember.getMemberId());
        Plan savedPlan = TestDataBuilder.buildWeightLossPlan(vipMember.getMemberId());

        when(memberService.getMemberById(vipMember.getMemberId())).thenReturn(vipMember);
        when(planRepository.findByMemberId(vipMember.getMemberId())).thenReturn(Optional.empty());
        when(planRepository.save(any(Plan.class))).thenReturn(savedPlan);
        when(statisticRepository.findByStatMonth(anyString())).thenReturn(Optional.empty());
        when(statisticRepository.save(any(Statistic.class))).thenReturn(new Statistic());
        when(historyRepository.save(any(History.class))).thenReturn(new History());

        planService.createPlan(request);

        verify(statisticRepository).save(any(Statistic.class));
    }

    @Test
    @DisplayName("测试创建计划时使用默认值")
    void testCreatePlanUsesDefaultValues() {
        PlanRequest request = new PlanRequest();
        request.setMemberId(vipMember.getMemberId());
        Plan savedPlan = TestDataBuilder.buildNewPlan(vipMember.getMemberId());

        when(memberService.getMemberById(vipMember.getMemberId())).thenReturn(vipMember);
        when(planRepository.findByMemberId(vipMember.getMemberId())).thenReturn(Optional.empty());
        when(planRepository.save(any(Plan.class))).thenReturn(savedPlan);
        when(statisticRepository.findByStatMonth(anyString())).thenReturn(Optional.empty());
        when(statisticRepository.save(any(Statistic.class))).thenReturn(new Statistic());
        when(historyRepository.save(any(History.class))).thenReturn(new History());

        Plan createdPlan = planService.createPlan(request);

        assertNotNull(createdPlan, "计划不应该为空");
    }

    @Test
    @DisplayName("测试已完成的计划可以创建新计划")
    void testCanCreateNewPlanWhenExistingPlanIsCompleted() {
        PlanRequest request = TestDataBuilder.buildGeneralPlanRequest(vipMember.getMemberId());
        Plan newPlan = TestDataBuilder.buildNewPlan(vipMember.getMemberId());

        when(memberService.getMemberById(vipMember.getMemberId())).thenReturn(vipMember);
        when(planRepository.findByMemberId(vipMember.getMemberId())).thenReturn(Optional.of(completedPlan));
        when(planRepository.save(any(Plan.class))).thenReturn(newPlan);
        when(statisticRepository.findByStatMonth(anyString())).thenReturn(Optional.empty());
        when(statisticRepository.save(any(Statistic.class))).thenReturn(new Statistic());
        when(historyRepository.save(any(History.class))).thenReturn(new History());

        Plan createdPlan = planService.createPlan(request);

        assertNotNull(createdPlan, "应该可以创建新计划");
        verify(planRepository).save(any(Plan.class));
    }
}
