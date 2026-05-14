package com.fitnesscenter.service;

import com.fitnesscenter.async.PlanAsyncService;
import com.fitnesscenter.builder.TestDataBuilder;
import com.fitnesscenter.dto.PlanRequest;
import com.fitnesscenter.model.Member;
import com.fitnesscenter.model.Plan;
import com.fitnesscenter.repository.HistoryRepository;
import com.fitnesscenter.repository.MemberRepository;
import com.fitnesscenter.repository.PlanRepository;
import com.fitnesscenter.repository.StatisticRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("计划异步服务测试")
class PlanAsyncServiceTest {

    @Mock
    private PlanRepository planRepository;

    @Mock
    private MemberRepository memberRepository;

    @Mock
    private HistoryRepository historyRepository;

    @Mock
    private StatisticRepository statisticRepository;

    @InjectMocks
    private PlanAsyncService planAsyncService;

    private Member vipMember;
    private Member regularMember;

    @BeforeEach
    void setUp() {
        planAsyncService.resetStats();
        vipMember = TestDataBuilder.buildVipMember();
        regularMember = TestDataBuilder.buildRegularMember();
    }

    @Test
    @DisplayName("测试异步创建计划 - 立即返回不阻塞")
    void testCreatePlanAsyncReturnsImmediately() {
        PlanRequest request = TestDataBuilder.buildWeightLossPlanRequest(vipMember.getMemberId());
        Plan savedPlan = TestDataBuilder.buildWeightLossPlan(vipMember.getMemberId());

        when(memberRepository.findByMemberId(vipMember.getMemberId())).thenReturn(Optional.of(vipMember));
        when(planRepository.findByMemberId(vipMember.getMemberId())).thenReturn(Optional.empty());
        when(planRepository.save(any(Plan.class))).thenReturn(savedPlan);

        long startTime = System.currentTimeMillis();
        PlanAsyncService.PlanGenerationResult result = planAsyncService.createPlanAsync(request);
        long endTime = System.currentTimeMillis();

        assertNotNull(result, "结果不应该为空");
        assertEquals("PROCESSING", result.getStatus(), "状态应该为PROCESSING");
        assertEquals(vipMember.getMemberId(), result.getMemberId(), "会员ID应该正确");
        assertTrue(endTime - startTime < 100, "应该立即返回，不阻塞");
        assertNotNull(result.getGenerationId(), "应该返回generationId");
    }

    @Test
    @DisplayName("测试异步创建计划 - 会员不存在")
    void testCreatePlanAsyncWhenMemberNotExists() {
        PlanRequest request = TestDataBuilder.buildWeightLossPlanRequest("NON_EXISTENT_MEMBER");

        when(memberRepository.findByMemberId("NON_EXISTENT_MEMBER")).thenReturn(Optional.empty());

        assertThrows(IllegalArgumentException.class,
                () -> planAsyncService.createPlanAsync(request),
                "会员不存在时应该抛出异常");
    }

    @Test
    @DisplayName("测试异步创建计划 - 已有进行中计划")
    void testCreatePlanAsyncWhenHasActivePlan() {
        PlanRequest request = TestDataBuilder.buildWeightLossPlanRequest(vipMember.getMemberId());
        Plan existingPlan = TestDataBuilder.buildWeightLossPlan(vipMember.getMemberId());

        when(memberRepository.findByMemberId(vipMember.getMemberId())).thenReturn(Optional.of(vipMember));
        when(planRepository.findByMemberId(vipMember.getMemberId())).thenReturn(Optional.of(existingPlan));

        assertThrows(IllegalStateException.class,
                () -> planAsyncService.createPlanAsync(request),
                "已有进行中计划时应该抛出异常");
    }

    @Test
    @DisplayName("测试后台Worker执行计划生成")
    void testBackgroundWorkerExecutesPlanGeneration() throws Exception {
        PlanRequest request = TestDataBuilder.buildWeightLossPlanRequest(vipMember.getMemberId());
        Plan savedPlan = TestDataBuilder.buildWeightLossPlan(vipMember.getMemberId());

        when(memberRepository.findByMemberId(vipMember.getMemberId())).thenReturn(Optional.of(vipMember));
        when(planRepository.findByMemberId(vipMember.getMemberId())).thenReturn(Optional.empty());
        when(planRepository.save(any(Plan.class))).thenReturn(savedPlan);

        PlanAsyncService.PlanGenerationResult result = planAsyncService.createPlanAsync(request);

        TimeUnit.MILLISECONDS.sleep(200);

        PlanAsyncService.PlanGenerationStatus status = planAsyncService.getGenerationStatus(result.getGenerationId());
        Plan plan = planAsyncService.getPlanByGenerationId(result.getGenerationId());

        assertEquals("COMPLETED", status.getStatus(), "状态应该为COMPLETED");
        assertNotNull(plan, "计划应该已生成");
        assertEquals(savedPlan.getPlanId(), plan.getPlanId(), "计划ID应该正确");
        assertEquals(1, planAsyncService.getPlanGenerationSuccesses(), "成功次数应该为1");
        assertTrue(planAsyncService.getPlanGenerationAttempts() >= 1, "尝试次数应该>=1");
    }

    @Test
    @DisplayName("测试查询计划生成状态 - PROCESSING")
    void testGetGenerationStatusProcessing() {
        PlanRequest request = TestDataBuilder.buildWeightLossPlanRequest(vipMember.getMemberId());
        Plan savedPlan = TestDataBuilder.buildWeightLossPlan(vipMember.getMemberId());

        when(memberRepository.findByMemberId(vipMember.getMemberId())).thenReturn(Optional.of(vipMember));
        when(planRepository.findByMemberId(vipMember.getMemberId())).thenReturn(Optional.empty());
        when(planRepository.save(any(Plan.class))).thenReturn(savedPlan);

        PlanAsyncService.PlanGenerationResult result = planAsyncService.createPlanAsync(request);

        PlanAsyncService.PlanGenerationStatus status = planAsyncService.getGenerationStatus(result.getGenerationId());

        assertEquals(result.getGenerationId(), status.getGenerationId(), "generationId应该正确");
        assertNotNull(status.getStatus(), "状态不应该为空");
    }

    @Test
    @DisplayName("测试查询计划生成状态 - NOT_FOUND")
    void testGetGenerationStatusNotFound() {
        PlanAsyncService.PlanGenerationStatus status = planAsyncService.getGenerationStatus("NON_EXISTENT_ID");

        assertEquals("NOT_FOUND", status.getStatus(), "状态应该为NOT_FOUND");
    }

    @Test
    @DisplayName("测试重置统计数据")
    void testResetStats() {
        PlanRequest request = TestDataBuilder.buildWeightLossPlanRequest(vipMember.getMemberId());
        Plan savedPlan = TestDataBuilder.buildWeightLossPlan(vipMember.getMemberId());

        when(memberRepository.findByMemberId(vipMember.getMemberId())).thenReturn(Optional.of(vipMember));
        when(planRepository.findByMemberId(vipMember.getMemberId())).thenReturn(Optional.empty());
        when(planRepository.save(any(Plan.class))).thenReturn(savedPlan);

        planAsyncService.createPlanAsync(request);

        planAsyncService.resetStats();

        assertEquals(0, planAsyncService.getPlanGenerationAttempts(), "尝试次数应该重置为0");
        assertEquals(0, planAsyncService.getPlanGenerationSuccesses(), "成功次数应该重置为0");
        assertEquals(0, planAsyncService.getPlanGenerationFailures(), "失败次数应该重置为0");
        assertEquals(0, planAsyncService.getPlanGenerationRetries(), "重试次数应该重置为0");
    }

    @Test
    @DisplayName("测试通过generationId获取计划")
    void testGetPlanByGenerationId() throws Exception {
        PlanRequest request = TestDataBuilder.buildWeightLossPlanRequest(vipMember.getMemberId());
        Plan savedPlan = TestDataBuilder.buildWeightLossPlan(vipMember.getMemberId());

        when(memberRepository.findByMemberId(vipMember.getMemberId())).thenReturn(Optional.of(vipMember));
        when(planRepository.findByMemberId(vipMember.getMemberId())).thenReturn(Optional.empty());
        when(planRepository.save(any(Plan.class))).thenReturn(savedPlan);

        PlanAsyncService.PlanGenerationResult result = planAsyncService.createPlanAsync(request);

        TimeUnit.MILLISECONDS.sleep(200);

        Plan plan = planAsyncService.getPlanByGenerationId(result.getGenerationId());

        assertNotNull(plan, "计划不应该为空");
        assertEquals(savedPlan.getPlanId(), plan.getPlanId(), "计划ID应该正确");
    }

    @Test
    @DisplayName("测试通过不存在的generationId获取计划")
    void testGetPlanByNonExistentGenerationId() {
        Plan plan = planAsyncService.getPlanByGenerationId("NON_EXISTENT_ID");

        assertNull(plan, "不存在的ID应该返回null");
    }

    @Test
    @DisplayName("测试多个异步计划生成")
    void testMultipleAsyncPlanGenerations() throws Exception {
        Member member1 = TestDataBuilder.buildVipMember();
        member1.setMemberId("MEMBER_1");
        Member member2 = TestDataBuilder.buildRegularMember();
        member2.setMemberId("MEMBER_2");

        PlanRequest request1 = TestDataBuilder.buildWeightLossPlanRequest("MEMBER_1");
        PlanRequest request2 = TestDataBuilder.buildGeneralPlanRequest("MEMBER_2");
        Plan savedPlan1 = TestDataBuilder.buildWeightLossPlan("MEMBER_1");
        Plan savedPlan2 = TestDataBuilder.buildNewPlan("MEMBER_2");

        when(memberRepository.findByMemberId("MEMBER_1")).thenReturn(Optional.of(member1));
        when(memberRepository.findByMemberId("MEMBER_2")).thenReturn(Optional.of(member2));
        when(planRepository.findByMemberId("MEMBER_1")).thenReturn(Optional.empty());
        when(planRepository.findByMemberId("MEMBER_2")).thenReturn(Optional.empty());
        when(planRepository.save(any(Plan.class))).thenReturn(savedPlan1, savedPlan2);

        PlanAsyncService.PlanGenerationResult result1 = planAsyncService.createPlanAsync(request1);
        PlanAsyncService.PlanGenerationResult result2 = planAsyncService.createPlanAsync(request2);

        TimeUnit.MILLISECONDS.sleep(300);

        assertEquals(2, planAsyncService.getPlanGenerationSuccesses(), "应该成功生成2个计划");

        PlanAsyncService.PlanGenerationStatus status1 = planAsyncService.getGenerationStatus(result1.getGenerationId());
        PlanAsyncService.PlanGenerationStatus status2 = planAsyncService.getGenerationStatus(result2.getGenerationId());

        assertEquals("COMPLETED", status1.getStatus(), "计划1应该完成");
        assertEquals("COMPLETED", status2.getStatus(), "计划2应该完成");
    }

    @Test
    @DisplayName("测试生成结果包含正确信息")
    void testPlanGenerationResultContainsCorrectInfo() {
        PlanRequest request = TestDataBuilder.buildWeightLossPlanRequest(vipMember.getMemberId());
        Plan savedPlan = TestDataBuilder.buildWeightLossPlan(vipMember.getMemberId());

        when(memberRepository.findByMemberId(vipMember.getMemberId())).thenReturn(Optional.of(vipMember));
        when(planRepository.findByMemberId(vipMember.getMemberId())).thenReturn(Optional.empty());
        when(planRepository.save(any(Plan.class))).thenReturn(savedPlan);

        PlanAsyncService.PlanGenerationResult result = planAsyncService.createPlanAsync(request);

        assertNotNull(result.getGenerationId(), "generationId不应该为空");
        assertEquals(vipMember.getMemberId(), result.getMemberId(), "会员ID应该正确");
        assertEquals("PROCESSING", result.getStatus(), "状态应该为PROCESSING");
        assertNotNull(result.getMessage(), "消息不应该为空");
        assertNotNull(result.getSubmittedAt(), "提交时间不应该为空");
    }
}
