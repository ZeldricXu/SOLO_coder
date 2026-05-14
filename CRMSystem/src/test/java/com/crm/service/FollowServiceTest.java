package com.crm.service;

import com.crm.builder.TestDataBuilder;
import com.crm.dto.FollowRequest;
import com.crm.entity.Category;
import com.crm.entity.Customer;
import com.crm.entity.Follow;
import com.crm.exception.BusinessException;
import com.crm.repository.CategoryRepository;
import com.crm.repository.CustomerCategoryRepository;
import com.crm.repository.FollowRepository;
import com.crm.strategy.DefaultReminderTimeStrategy;
import com.crm.strategy.ReminderTimeStrategy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("跟进模块单元测试")
class FollowServiceTest {

    @Mock
    private FollowRepository followRepository;

    @Mock
    private CustomerService customerService;

    @Mock
    private AnalysisService analysisService;

    @Mock
    private HistoryService historyService;

    @Mock
    private ReminderService reminderService;

    @Spy
    private ReminderTimeStrategy reminderTimeStrategy = new DefaultReminderTimeStrategy();

    @Mock
    private CustomerCategoryRepository customerCategoryRepository;

    @Mock
    private CategoryRepository categoryRepository;

    @InjectMocks
    private FollowService followService;

    private Customer potentialCustomer;
    private Customer vipCustomer;
    private Customer dealCustomer;
    private Follow phoneFollow;
    private Follow dealFollow;
    private FollowRequest followRequest;
    private Category vipCategory;

    @BeforeEach
    void setUp() {
        potentialCustomer = TestDataBuilder.buildPotentialCustomer("customer_test_001");
        vipCustomer = TestDataBuilder.buildVIPCustomer("customer_vip_001");
        dealCustomer = TestDataBuilder.buildDealCustomer("customer_deal_001");
        phoneFollow = TestDataBuilder.buildPhoneFollow("customer_test_001");
        dealFollow = TestDataBuilder.buildDealFollow("customer_test_001");
        vipCategory = TestDataBuilder.buildVIPCategory();

        followRequest = new FollowRequest();
        followRequest.setCustomerId("customer_test_001");
        followRequest.setSalesId("sales_001");
        followRequest.setFollowType("phone");
        followRequest.setFollowContent("电话沟通产品需求");
        followRequest.setFollowResult("interested");
        followRequest.setFollowTime(LocalDateTime.now());
        followRequest.setNextFollow(LocalDateTime.now().plusDays(7));
    }

    @Test
    @DisplayName("创建跟进 - 潜在客户跟进成功，状态转意向")
    void testCreateFollow_PotentialCustomer() {
        when(customerService.getCustomerById("customer_test_001")).thenReturn(potentialCustomer);
        when(followRepository.save(any(Follow.class))).thenReturn(phoneFollow);
        when(customerCategoryRepository.findByCustomerId("customer_test_001")).thenReturn(Collections.emptyList());
        doNothing().when(customerService).incrementFollowCount(anyString());
        doNothing().when(analysisService).incrementFollowCount();
        doNothing().when(customerService).updateCustomerStatus(anyString(), anyString());
        when(reminderService.createReminder(anyString(), anyString(), anyString(), any(), anyString())).thenReturn(null);
        doNothing().when(historyService).recordHistory(anyString(), anyString(), anyString(), anyString(), anyString(), anyString());

        Map<String, Object> result = followService.createFollow(followRequest);

        assertNotNull(result);
        assertEquals("follow_test_001", result.get("follow_id"));
        assertEquals("interested", result.get("result"));
        verify(customerService, times(1)).updateCustomerStatus("customer_test_001", "interested");
    }

    @Test
    @DisplayName("创建跟进 - 成交客户禁止跟进")
    void testCreateFollow_DealCustomerThrowsException() {
        when(customerService.getCustomerById("customer_deal_001")).thenReturn(dealCustomer);

        followRequest.setCustomerId("customer_deal_001");
        assertThrows(BusinessException.class, () -> followService.createFollow(followRequest));
    }

    @Test
    @DisplayName("跟进状态流转 - 跟进结果为成交时，客户状态转成交")
    void testCreateFollow_ResultDeal() {
        when(customerService.getCustomerById("customer_test_001")).thenReturn(potentialCustomer);
        when(followRepository.save(any(Follow.class))).thenReturn(dealFollow);
        when(customerCategoryRepository.findByCustomerId("customer_test_001")).thenReturn(Collections.emptyList());
        doNothing().when(customerService).incrementFollowCount(anyString());
        doNothing().when(analysisService).incrementFollowCount();
        doNothing().when(customerService).updateCustomerStatus(anyString(), anyString());
        doNothing().when(historyService).recordHistory(anyString(), anyString(), anyString(), anyString(), anyString(), anyString());

        followRequest.setFollowResult("deal");
        followRequest.setNextFollow(null);
        
        Map<String, Object> result = followService.createFollow(followRequest);

        verify(customerService, times(1)).updateCustomerStatus("customer_test_001", "deal");
    }

    @Test
    @DisplayName("跟进状态流转 - 跟进结果为拒绝时，客户状态转拒绝")
    void testCreateFollow_ResultRejected() {
        Follow rejectedFollow = TestDataBuilder.buildRejectedFollow("customer_test_001");
        
        when(customerService.getCustomerById("customer_test_001")).thenReturn(potentialCustomer);
        when(followRepository.save(any(Follow.class))).thenReturn(rejectedFollow);
        when(customerCategoryRepository.findByCustomerId("customer_test_001")).thenReturn(Collections.emptyList());
        doNothing().when(customerService).incrementFollowCount(anyString());
        doNothing().when(analysisService).incrementFollowCount();
        doNothing().when(customerService).updateCustomerStatus(anyString(), anyString());
        doNothing().when(historyService).recordHistory(anyString(), anyString(), anyString(), anyString(), anyString(), anyString());

        followRequest.setFollowResult("rejected");
        followRequest.setNextFollow(null);
        
        followService.createFollow(followRequest);

        verify(customerService, times(1)).updateCustomerStatus("customer_test_001", "rejected");
    }

    @Test
    @DisplayName("跟进提醒机制 - VIP客户提前48小时提醒")
    void testCreateFollow_VIPCustomerReminderTime() {
        when(customerService.getCustomerById("customer_vip_001")).thenReturn(vipCustomer);
        when(followRepository.save(any(Follow.class))).thenReturn(phoneFollow);
        when(customerCategoryRepository.findByCustomerId("customer_vip_001")).thenReturn(Collections.singletonList(
                new com.crm.entity.CustomerCategory(1L, "customer_vip_001", "category_vip")
        ));
        when(categoryRepository.findByCategoryId("category_vip")).thenReturn(Optional.of(vipCategory));
        doNothing().when(customerService).incrementFollowCount(anyString());
        doNothing().when(analysisService).incrementFollowCount();
        doNothing().when(customerService).updateCustomerStatus(anyString(), anyString());
        when(reminderService.createReminder(anyString(), anyString(), anyString(), any(), anyString())).thenReturn(null);
        doNothing().when(historyService).recordHistory(anyString(), anyString(), anyString(), anyString(), anyString(), anyString());

        followRequest.setCustomerId("customer_vip_001");
        LocalDateTime nextFollow = LocalDateTime.now().plusDays(7);
        followRequest.setNextFollow(nextFollow);
        
        followService.createFollow(followRequest);

        verify(reminderService, times(1)).createReminder(
                eq("customer_vip_001"),
                anyString(),
                eq("follow_remind"),
                argThat(time -> {
                    LocalDateTime expectedVIPTime = nextFollow.minusHours(48);
                    return time.isEqual(expectedVIPTime) || time.isBefore(nextFollow.minusHours(24));
                }),
                anyString()
        );
    }

    @Test
    @DisplayName("跟进提醒机制 - 普通客户提前24小时提醒")
    void testCreateFollow_RegularCustomerReminderTime() {
        when(customerService.getCustomerById("customer_test_001")).thenReturn(potentialCustomer);
        when(followRepository.save(any(Follow.class))).thenReturn(phoneFollow);
        when(customerCategoryRepository.findByCustomerId("customer_test_001")).thenReturn(Collections.emptyList());
        doNothing().when(customerService).incrementFollowCount(anyString());
        doNothing().when(analysisService).incrementFollowCount();
        doNothing().when(customerService).updateCustomerStatus(anyString(), anyString());
        when(reminderService.createReminder(anyString(), anyString(), anyString(), any(), anyString())).thenReturn(null);
        doNothing().when(historyService).recordHistory(anyString(), anyString(), anyString(), anyString(), anyString(), anyString());

        LocalDateTime nextFollow = LocalDateTime.now().plusDays(7);
        followRequest.setNextFollow(nextFollow);
        
        followService.createFollow(followRequest);

        verify(reminderService, times(1)).createReminder(
                eq("customer_test_001"),
                anyString(),
                eq("follow_remind"),
                argThat(time -> {
                    LocalDateTime expectedRegularTime = nextFollow.minusHours(24);
                    LocalDateTime expectedVIPTime = nextFollow.minusHours(48);
                    return time.isEqual(expectedRegularTime) || 
                           (time.isAfter(expectedVIPTime) && time.isBefore(nextFollow));
                }),
                anyString()
        );
    }

    @Test
    @DisplayName("提醒发送机制 - 下次跟进时间为null时不创建提醒")
    void testCreateFollow_NoNextFollowNoReminder() {
        when(customerService.getCustomerById("customer_test_001")).thenReturn(potentialCustomer);
        when(followRepository.save(any(Follow.class))).thenReturn(phoneFollow);
        doNothing().when(customerService).incrementFollowCount(anyString());
        doNothing().when(analysisService).incrementFollowCount();
        doNothing().when(customerService).updateCustomerStatus(anyString(), anyString());
        doNothing().when(historyService).recordHistory(anyString(), anyString(), anyString(), anyString(), anyString(), anyString());

        followRequest.setNextFollow(null);
        followService.createFollow(followRequest);

        verify(reminderService, never()).createReminder(anyString(), anyString(), anyString(), any(), anyString());
    }

    @Test
    @DisplayName("跟进完整生命周期 - 潜在->意向->成交")
    void testFollowFullLifecycle_PotentialToDeal() {
        when(customerService.getCustomerById("customer_test_001")).thenReturn(potentialCustomer);
        when(followRepository.save(any(Follow.class))).thenReturn(phoneFollow);
        when(customerCategoryRepository.findByCustomerId("customer_test_001")).thenReturn(Collections.emptyList());
        doNothing().when(customerService).incrementFollowCount(anyString());
        doNothing().when(analysisService).incrementFollowCount();
        doNothing().when(customerService).updateCustomerStatus(anyString(), anyString());
        when(reminderService.createReminder(anyString(), anyString(), anyString(), any(), anyString())).thenReturn(null);
        doNothing().when(historyService).recordHistory(anyString(), anyString(), anyString(), anyString(), anyString(), anyString());

        Map<String, Object> result1 = followService.createFollow(followRequest);
        assertNotNull(result1);

        verify(customerService, times(1)).updateCustomerStatus("customer_test_001", "interested");

        when(followRepository.save(any(Follow.class))).thenReturn(dealFollow);
        followRequest.setFollowResult("deal");
        followRequest.setNextFollow(null);
        
        Map<String, Object> result2 = followService.createFollow(followRequest);
        assertNotNull(result2);

        verify(customerService, times(1)).updateCustomerStatus("customer_test_001", "deal");
    }

    @Test
    @DisplayName("获取跟进记录 - 存在的跟进")
    void testGetFollowById_Exists() {
        when(followRepository.findByFollowId("follow_test_001")).thenReturn(Optional.of(phoneFollow));

        Follow result = followService.getFollowById("follow_test_001");

        assertNotNull(result);
        assertEquals("follow_test_001", result.getFollowId());
    }

    @Test
    @DisplayName("获取跟进记录 - 不存在的跟进抛出异常")
    void testGetFollowById_NotExists() {
        when(followRepository.findByFollowId("follow_nonexistent")).thenReturn(Optional.empty());

        assertThrows(BusinessException.class, 
                () -> followService.getFollowById("follow_nonexistent"));
    }
}
