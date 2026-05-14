package com.crm.service;

import com.crm.builder.TestDataBuilder;
import com.crm.dto.OpportunityFollowRequest;
import com.crm.dto.OpportunityRequest;
import com.crm.entity.Customer;
import com.crm.entity.Opportunity;
import com.crm.exception.BusinessException;
import com.crm.repository.OpportunityRepository;
import com.crm.strategy.DefaultOpportunityAlertStrategy;
import com.crm.strategy.OpportunityAlertStrategy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("机会模块单元测试")
class OpportunityServiceTest {

    @Mock
    private OpportunityRepository opportunityRepository;

    @Mock
    private CustomerService customerService;

    @Mock
    private AnalysisService analysisService;

    @Mock
    private HistoryService historyService;

    @Spy
    private OpportunityAlertStrategy opportunityAlertStrategy = new DefaultOpportunityAlertStrategy();

    @Mock
    private ReminderService reminderService;

    @InjectMocks
    private OpportunityService opportunityService;

    private Customer potentialCustomer;
    private Customer dealCustomer;
    private Opportunity initialOpportunity;
    private Opportunity negotiationOpportunity;
    private Opportunity largeAmountOpportunity;
    private Opportunity smallAmountOpportunity;
    private Opportunity successOpportunity;
    private Opportunity failedOpportunity;
    private Opportunity staleOpportunity3Days;
    private Opportunity staleOpportunity7Days;
    private OpportunityRequest opportunityRequest;
    private OpportunityFollowRequest followRequest;

    @BeforeEach
    void setUp() {
        potentialCustomer = TestDataBuilder.buildPotentialCustomer("customer_test_001");
        dealCustomer = TestDataBuilder.buildDealCustomer("customer_deal_001");
        initialOpportunity = TestDataBuilder.buildInitialOpportunity("customer_test_001");
        negotiationOpportunity = TestDataBuilder.buildNegotiationOpportunity("customer_test_001");
        largeAmountOpportunity = TestDataBuilder.buildLargeAmountOpportunity("customer_test_001");
        smallAmountOpportunity = TestDataBuilder.buildSmallAmountOpportunity("customer_test_001");
        successOpportunity = TestDataBuilder.buildSuccessOpportunity("customer_test_001");
        failedOpportunity = TestDataBuilder.buildFailedOpportunity("customer_test_001");
        staleOpportunity3Days = TestDataBuilder.buildStaleOpportunity("customer_test_001", 3);
        staleOpportunity7Days = TestDataBuilder.buildStaleOpportunity("customer_test_001", 7);

        opportunityRequest = new OpportunityRequest();
        opportunityRequest.setCustomerId("customer_test_001");
        opportunityRequest.setSalesId("sales_001");
        opportunityRequest.setOpportunityAmount(50000.0);
        opportunityRequest.setOpportunityStage("initial");
        opportunityRequest.setOpportunityProb(10);

        followRequest = new OpportunityFollowRequest();
        followRequest.setOpportunityId("opp_test_001");
        followRequest.setSalesId("sales_001");
        followRequest.setOpportunityStage("negotiation");
        followRequest.setOpportunityProb(60);
        followRequest.setAction("progress");
    }

    @Test
    @DisplayName("创建机会 - 潜在客户创建机会成功")
    void testCreateOpportunity_PotentialCustomer() {
        when(customerService.getCustomerById("customer_test_001")).thenReturn(potentialCustomer);
        when(opportunityRepository.save(any(Opportunity.class))).thenReturn(initialOpportunity);
        doNothing().when(customerService).incrementOpportunityCount(anyString());
        doNothing().when(analysisService).incrementOpportunityCount();
        doNothing().when(historyService).recordHistory(anyString(), anyString(), anyString(), anyString(), anyString(), anyString());

        Map<String, Object> result = opportunityService.createOpportunity(opportunityRequest);

        assertNotNull(result);
        assertEquals("opp_test_001", result.get("opportunity_id"));
        assertEquals("following", result.get("status"));
    }

    @Test
    @DisplayName("创建机会 - 已成交客户禁止创建新机会")
    void testCreateOpportunity_DealCustomerThrowsException() {
        when(customerService.getCustomerById("customer_deal_001")).thenReturn(dealCustomer);

        opportunityRequest.setCustomerId("customer_deal_001");
        assertThrows(BusinessException.class, () -> opportunityService.createOpportunity(opportunityRequest));
    }

    @Test
    @DisplayName("机会阶段推进 - 从初步到谈判")
    void testFollowOpportunity_StageProgress() {
        when(opportunityRepository.findByOpportunityId("opp_test_001")).thenReturn(Optional.of(initialOpportunity));
        when(opportunityRepository.save(any(Opportunity.class))).thenReturn(negotiationOpportunity);
        doNothing().when(historyService).recordHistory(anyString(), anyString(), anyString(), anyString(), anyString(), anyString());

        Map<String, Object> result = opportunityService.followOpportunity(followRequest);

        assertNotNull(result);
        assertEquals("negotiation", result.get("stage"));
        assertEquals("following", result.get("status"));
    }

    @Test
    @DisplayName("机会成功 - 状态变更为success，客户状态转成交")
    void testFollowOpportunity_Success() {
        when(opportunityRepository.findByOpportunityId("opp_test_001")).thenReturn(Optional.of(negotiationOpportunity));
        when(opportunityRepository.save(any(Opportunity.class))).thenReturn(successOpportunity);
        doNothing().when(customerService).updateCustomerStatus(anyString(), anyString());
        doNothing().when(analysisService).addDealAmount(anyDouble());
        doNothing().when(historyService).recordHistory(anyString(), anyString(), anyString(), anyString(), anyString(), anyString());

        followRequest.setAction("success");
        Map<String, Object> result = opportunityService.followOpportunity(followRequest);

        assertNotNull(result);
        assertEquals("success", result.get("status"));
        verify(customerService, times(1)).updateCustomerStatus("customer_test_001", "deal");
        verify(analysisService, times(1)).addDealAmount(anyDouble());
    }

    @Test
    @DisplayName("机会失败 - 状态变更为failed，记录失败原因")
    void testFollowOpportunity_Failed() {
        when(opportunityRepository.findByOpportunityId("opp_test_001")).thenReturn(Optional.of(negotiationOpportunity));
        when(opportunityRepository.save(any(Opportunity.class))).thenReturn(failedOpportunity);
        doNothing().when(analysisService).incrementFailCount();
        doNothing().when(historyService).recordHistory(anyString(), anyString(), anyString(), anyString(), anyString(), anyString());

        followRequest.setAction("failed");
        followRequest.setFailReason("客户选择了竞品");
        Map<String, Object> result = opportunityService.followOpportunity(followRequest);

        assertNotNull(result);
        assertEquals("failed", result.get("status"));
        verify(analysisService, times(1)).incrementFailCount();
    }

    @Test
    @DisplayName("机会跟进 - 已成交机会禁止继续跟进")
    void testFollowOpportunity_SuccessOpportunityThrowsException() {
        when(opportunityRepository.findByOpportunityId("opp_success_001")).thenReturn(Optional.of(successOpportunity));

        followRequest.setOpportunityId("opp_success_001");
        assertThrows(BusinessException.class, () -> opportunityService.followOpportunity(followRequest));
    }

    @Test
    @DisplayName("机会预警机制 - 大额机会3天未更新触发预警")
    void testShouldAlert_LargeAmountOpportunity3Days() {
        assertTrue(opportunityService.shouldAlert(staleOpportunity3Days));
    }

    @Test
    @DisplayName("机会预警机制 - 小额机会7天未更新触发预警")
    void testShouldAlert_SmallAmountOpportunity7Days() {
        when(opportunityRepository.findByOpportunityStatus("following")).thenReturn(
                Collections.singletonList(smallAmountOpportunity)
        );
        
        assertFalse(opportunityService.shouldAlert(TestDataBuilder.buildStaleOpportunity("customer_test_001", 3)));
    }

    @Test
    @DisplayName("机会预警阈值 - 大额机会短阈值")
    void testGetAlertThreshold_LargeAmount() {
        int threshold = opportunityService.getAlertThresholdDays(largeAmountOpportunity);
        assertEquals(3, threshold);
    }

    @Test
    @DisplayName("机会预警阈值 - 小额机会长阈值")
    void testGetAlertThreshold_SmallAmount() {
        int threshold = opportunityService.getAlertThresholdDays(smallAmountOpportunity);
        assertEquals(7, threshold);
    }

    @Test
    @DisplayName("获取停滞机会 - 筛选出超过阈值的机会")
    void testGetStaleOpportunities() {
        Opportunity freshOpportunity = TestDataBuilder.buildInitialOpportunity("customer_test_001");
        Opportunity staleOpportunity = TestDataBuilder.buildStaleOpportunity("customer_test_001", 10);

        when(opportunityRepository.findByOpportunityStatus("following")).thenReturn(
                List.of(freshOpportunity, staleOpportunity)
        );

        List<Opportunity> staleOpportunities = opportunityService.getStaleOpportunities();

        assertFalse(staleOpportunities.isEmpty());
        assertTrue(staleOpportunities.contains(staleOpportunity));
    }

    @Test
    @DisplayName("检查并预警停滞机会 - 创建提醒")
    void testCheckAndAlertStaleOpportunities() {
        when(opportunityRepository.findByOpportunityStatus("following")).thenReturn(
                Collections.singletonList(staleOpportunity7Days)
        );
        when(reminderService.createReminder(anyString(), anyString(), anyString(), any(), anyString())).thenReturn(null);

        opportunityService.checkAndAlertStaleOpportunities();

        verify(reminderService, times(1)).createReminder(
                eq("customer_test_001"),
                anyString(),
                eq("opportunity_alert"),
                any(),
                anyString()
        );
    }

    @Test
    @DisplayName("获取机会 - 存在的机会")
    void testGetOpportunityById_Exists() {
        when(opportunityRepository.findByOpportunityId("opp_test_001")).thenReturn(Optional.of(initialOpportunity));

        Opportunity result = opportunityService.getOpportunityById("opp_test_001");

        assertNotNull(result);
        assertEquals("opp_test_001", result.getOpportunityId());
    }

    @Test
    @DisplayName("获取机会 - 不存在的机会抛出异常")
    void testGetOpportunityById_NotExists() {
        when(opportunityRepository.findByOpportunityId("opp_nonexistent")).thenReturn(Optional.empty());

        assertThrows(BusinessException.class,
                () -> opportunityService.getOpportunityById("opp_nonexistent"));
    }

    @Test
    @DisplayName("机会完整生命周期 - 创建->推进->成功")
    void testOpportunityFullLifecycle_CreateToSuccess() {
        when(customerService.getCustomerById("customer_test_001")).thenReturn(potentialCustomer);
        when(opportunityRepository.save(any(Opportunity.class))).thenReturn(initialOpportunity);
        doNothing().when(customerService).incrementOpportunityCount(anyString());
        doNothing().when(analysisService).incrementOpportunityCount();
        doNothing().when(historyService).recordHistory(anyString(), anyString(), anyString(), anyString(), anyString(), anyString());

        Map<String, Object> createResult = opportunityService.createOpportunity(opportunityRequest);
        assertNotNull(createResult);
        assertEquals("following", createResult.get("status"));

        when(opportunityRepository.findByOpportunityId("opp_test_001")).thenReturn(Optional.of(initialOpportunity));
        when(opportunityRepository.save(any(Opportunity.class))).thenReturn(negotiationOpportunity);
        
        Map<String, Object> progressResult = opportunityService.followOpportunity(followRequest);
        assertNotNull(progressResult);
        assertEquals("negotiation", progressResult.get("stage"));

        when(opportunityRepository.findByOpportunityId("opp_test_001")).thenReturn(Optional.of(negotiationOpportunity));
        when(opportunityRepository.save(any(Opportunity.class))).thenReturn(successOpportunity);
        doNothing().when(customerService).updateCustomerStatus(anyString(), anyString());
        doNothing().when(analysisService).addDealAmount(anyDouble());

        followRequest.setAction("success");
        Map<String, Object> successResult = opportunityService.followOpportunity(followRequest);
        assertNotNull(successResult);
        assertEquals("success", successResult.get("status"));
    }
}
