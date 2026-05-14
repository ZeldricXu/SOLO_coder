package com.crm.service;

import com.crm.builder.TestDataBuilder;
import com.crm.dto.CustomerRequest;
import com.crm.entity.Customer;
import com.crm.exception.BusinessException;
import com.crm.repository.CustomerRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("客户管理模块单元测试")
class CustomerServiceTest {

    @Mock
    private CustomerRepository customerRepository;

    @Mock
    private AnalysisService analysisService;

    @Mock
    private HistoryService historyService;

    @Mock
    private CategoryMatchingWorker categoryMatchingWorker;

    @Mock
    private CustomerValueService customerValueService;

    @InjectMocks
    private CustomerService customerService;

    private Customer potentialCustomer;
    private Customer interestedCustomer;
    private Customer dealCustomer;
    private CustomerRequest customerRequest;

    @BeforeEach
    void setUp() {
        potentialCustomer = TestDataBuilder.buildPotentialCustomer("customer_test_001");
        interestedCustomer = TestDataBuilder.buildInterestedCustomer("customer_test_002");
        dealCustomer = TestDataBuilder.buildDealCustomer("customer_test_003");
        
        customerRequest = new CustomerRequest();
        customerRequest.setCustomerName("测试客户");
        customerRequest.setCustomerType("enterprise");
        customerRequest.setCustomerSource("marketing");
        customerRequest.setCustomerContact("13800138000");
        customerRequest.setCustomerAddress("北京市朝阳区");
    }

    @Test
    @DisplayName("创建客户 - 成功创建并返回客户ID")
    void testCreateCustomer_Success() {
        when(customerRepository.save(any(Customer.class))).thenReturn(potentialCustomer);
        doNothing().when(analysisService).incrementCustomerCount();
        doNothing().when(historyService).recordHistory(anyString(), anyString(), anyString(), anyString(), anyString(), anyString());
        doNothing().when(categoryMatchingWorker).processCustomerCategoriesAsync(anyString());

        Map<String, Object> result = customerService.createCustomer(customerRequest);

        assertNotNull(result);
        assertEquals("customer_test_001", result.get("customer_id"));
        assertEquals("potential", result.get("status"));
        verify(customerRepository, times(1)).save(any(Customer.class));
        verify(analysisService, times(1)).incrementCustomerCount();
        verify(categoryMatchingWorker, times(1)).processCustomerCategoriesAsync(anyString());
    }

    @Test
    @DisplayName("获取客户 - 存在的客户")
    void testGetCustomerById_Exists() {
        when(customerRepository.findByCustomerId("customer_test_001"))
                .thenReturn(Optional.of(potentialCustomer));

        Customer result = customerService.getCustomerById("customer_test_001");

        assertNotNull(result);
        assertEquals("customer_test_001", result.getCustomerId());
        assertEquals("测试客户", result.getCustomerName());
        assertEquals("potential", result.getCustomerStatus());
    }

    @Test
    @DisplayName("获取客户 - 不存在的客户抛出异常")
    void testGetCustomerById_NotExists() {
        when(customerRepository.findByCustomerId("customer_nonexistent"))
                .thenReturn(Optional.empty());

        assertThrows(BusinessException.class, 
                () -> customerService.getCustomerById("customer_nonexistent"));
    }

    @Test
    @DisplayName("客户状态流转 - 潜在客户转意向客户")
    void testUpdateCustomerStatus_PotentialToInterested() {
        when(customerRepository.findByCustomerId("customer_test_001"))
                .thenReturn(Optional.of(potentialCustomer));
        when(customerRepository.save(any(Customer.class))).thenReturn(interestedCustomer);
        doNothing().when(historyService).recordHistory(anyString(), anyString(), anyString(), anyString(), anyString(), anyString());

        Customer result = customerService.updateCustomerStatus("customer_test_001", "interested");

        assertNotNull(result);
        assertEquals("interested", result.getCustomerStatus());
    }

    @Test
    @DisplayName("客户状态流转 - 意向客户转成交客户")
    void testUpdateCustomerStatus_InterestedToDeal() {
        when(customerRepository.findByCustomerId("customer_test_002"))
                .thenReturn(Optional.of(interestedCustomer));
        when(customerRepository.save(any(Customer.class))).thenReturn(dealCustomer);
        doNothing().when(historyService).recordHistory(anyString(), anyString(), anyString(), anyString(), anyString(), anyString());

        Customer result = customerService.updateCustomerStatus("customer_test_002", "deal");

        assertNotNull(result);
        assertEquals("deal", result.getCustomerStatus());
    }

    @Test
    @DisplayName("客户状态流转 - 已成交客户禁止更新状态")
    void testUpdateCustomerStatus_DealCustomerThrowsException() {
        when(customerRepository.findByCustomerId("customer_test_003"))
                .thenReturn(Optional.of(dealCustomer));

        assertThrows(BusinessException.class,
                () -> customerService.updateCustomerStatus("customer_test_003", "interested"));
    }

    @Test
    @DisplayName("客户价值评估 - 潜在客户为低价值")
    void testEvaluateCustomerValue_PotentialCustomer() {
        when(customerRepository.findByCustomerId("customer_test_001"))
                .thenReturn(Optional.of(potentialCustomer));
        when(customerValueService.evaluateCustomerValue(potentialCustomer))
                .thenReturn(CustomerValueService.CustomerValue.LOW);

        CustomerValueService.CustomerValue result = customerService.evaluateCustomerValue("customer_test_001");

        assertEquals(CustomerValueService.CustomerValue.LOW, result);
    }

    @Test
    @DisplayName("客户价值评估 - 成交客户为高价值")
    void testEvaluateCustomerValue_DealCustomer() {
        when(customerRepository.findByCustomerId("customer_test_003"))
                .thenReturn(Optional.of(dealCustomer));
        when(customerValueService.evaluateCustomerValue(dealCustomer))
                .thenReturn(CustomerValueService.CustomerValue.HIGH);

        CustomerValueService.CustomerValue result = customerService.evaluateCustomerValue("customer_test_003");

        assertEquals(CustomerValueService.CustomerValue.HIGH, result);
    }

    @Test
    @DisplayName("增加跟进计数")
    void testIncrementFollowCount() {
        when(customerRepository.findByCustomerId("customer_test_001"))
                .thenReturn(Optional.of(potentialCustomer));
        when(customerRepository.save(any(Customer.class))).thenReturn(potentialCustomer);

        assertDoesNotThrow(() -> customerService.incrementFollowCount("customer_test_001"));
        verify(customerRepository, times(1)).save(any(Customer.class));
    }

    @Test
    @DisplayName("增加机会计数")
    void testIncrementOpportunityCount() {
        when(customerRepository.findByCustomerId("customer_test_001"))
                .thenReturn(Optional.of(potentialCustomer));
        when(customerRepository.save(any(Customer.class))).thenReturn(potentialCustomer);

        assertDoesNotThrow(() -> customerService.incrementOpportunityCount("customer_test_001"));
        verify(customerRepository, times(1)).save(any(Customer.class));
    }
}
