package com.crm.service;

import com.crm.builder.TestDataBuilder;
import com.crm.entity.Category;
import com.crm.entity.Customer;
import com.crm.repository.CategoryRepository;
import com.crm.repository.CustomerCategoryRepository;
import com.crm.repository.CustomerRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collections;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("分类模块单元测试")
class CategoryMatchingWorkerTest {

    @Mock
    private CustomerRepository customerRepository;

    @Mock
    private CategoryRepository categoryRepository;

    @Mock
    private CustomerCategoryRepository customerCategoryRepository;

    @Mock
    private HistoryService historyService;

    @InjectMocks
    private CategoryMatchingWorker categoryMatchingWorker;

    private Customer potentialCustomer;
    private Customer highValueCustomer;
    private Category vipCategory;
    private Category regularCategory;

    @BeforeEach
    void setUp() {
        potentialCustomer = TestDataBuilder.buildPotentialCustomer("customer_test_001");
        
        highValueCustomer = TestDataBuilder.buildDealCustomer("customer_high_value");
        
        vipCategory = TestDataBuilder.buildVIPCategory();
        regularCategory = TestDataBuilder.buildRegularCategory();

        categoryMatchingWorker.resetCounters();
    }

    @Test
    @DisplayName("分类标记异步处理 - 处理后立即返回，不阻塞主线程")
    void testProcessCustomerCategoriesAsync_NonBlocking() throws InterruptedException {
        when(customerRepository.findByCustomerId("customer_test_001")).thenReturn(Optional.of(potentialCustomer));
        when(categoryRepository.findByCategoryStatus("active")).thenReturn(Collections.singletonList(regularCategory));
        when(customerCategoryRepository.findByCustomerId("customer_test_001")).thenReturn(Collections.emptyList());
        when(customerCategoryRepository.save(any())).thenReturn(null);
        doNothing().when(historyService).recordHistory(anyString(), anyString(), anyString(), anyString(), anyString(), anyString());

        long startTime = System.currentTimeMillis();
        
        CountDownLatch latch = new CountDownLatch(1);
        
        categoryMatchingWorker.processCustomerCategoriesAsync("customer_test_001");
        
        long elapsedTime = System.currentTimeMillis() - startTime;
        
        assertTrue(elapsedTime < 100, "异步调用应该立即返回，不应阻塞");
        
        Thread.sleep(1500);
        assertTrue(categoryMatchingWorker.getProcessedCount() >= 1);
    }

    @Test
    @DisplayName("分类匹配 - 高价值客户匹配VIP分类")
    void testProcessCustomerCategories_HighValueCustomerMatchesVIP() throws InterruptedException {
        when(customerRepository.findByCustomerId("customer_high_value")).thenReturn(Optional.of(highValueCustomer));
        when(categoryRepository.findByCategoryStatus("active")).thenReturn(Collections.singletonList(vipCategory));
        when(customerCategoryRepository.findByCustomerId("customer_high_value")).thenReturn(Collections.emptyList());
        when(customerCategoryRepository.save(any())).thenReturn(null);
        doNothing().when(historyService).recordHistory(anyString(), anyString(), anyString(), anyString(), anyString(), anyString());

        categoryMatchingWorker.processCustomerCategoriesAsync("customer_high_value");
        
        Thread.sleep(1500);
        
        assertEquals(1, categoryMatchingWorker.getProcessedCount());
        assertEquals(0, categoryMatchingWorker.getFailedCount());
    }

    @Test
    @DisplayName("分类匹配 - 普通客户匹配普通分类")
    void testProcessCustomerCategories_RegularCustomerMatchesRegular() throws InterruptedException {
        when(customerRepository.findByCustomerId("customer_test_001")).thenReturn(Optional.of(potentialCustomer));
        when(categoryRepository.findByCategoryStatus("active")).thenReturn(Collections.singletonList(regularCategory));
        when(customerCategoryRepository.findByCustomerId("customer_test_001")).thenReturn(Collections.emptyList());
        when(customerCategoryRepository.save(any())).thenReturn(null);
        doNothing().when(historyService).recordHistory(anyString(), anyString(), anyString(), anyString(), anyString(), anyString());

        categoryMatchingWorker.processCustomerCategoriesAsync("customer_test_001");
        
        Thread.sleep(1500);
        
        assertEquals(1, categoryMatchingWorker.getProcessedCount());
    }

    @Test
    @DisplayName("重试机制 - 首次失败后重试成功")
    void testProcessCustomerCategories_RetrySuccess() throws InterruptedException {
        when(customerRepository.findByCustomerId("customer_test_001"))
                .thenThrow(new RuntimeException("第一次失败"))
                .thenReturn(Optional.of(potentialCustomer));
        when(categoryRepository.findByCategoryStatus("active")).thenReturn(Collections.emptyList());

        categoryMatchingWorker.processCustomerCategoriesAsync("customer_test_001");
        
        Thread.sleep(4000);
        
        assertEquals(1, categoryMatchingWorker.getProcessedCount());
        assertTrue(categoryMatchingWorker.getRetryCount() >= 1);
    }

    @Test
    @DisplayName("重试机制 - 连续失败后记录失败")
    void testProcessCustomerCategories_RetryExhausted() throws InterruptedException {
        when(customerRepository.findByCustomerId("customer_test_001"))
                .thenThrow(new RuntimeException("持续失败"));

        categoryMatchingWorker.processCustomerCategoriesAsync("customer_test_001");
        
        Thread.sleep(7000);
        
        assertEquals(1, categoryMatchingWorker.getFailedCount());
        assertEquals(3, categoryMatchingWorker.getRetryCount());
    }

    @Test
    @DisplayName("分类标记 - 已分配分类不再重复分配")
    void testProcessCustomerCategories_NoDuplicateAssignment() throws InterruptedException {
        when(customerRepository.findByCustomerId("customer_test_001")).thenReturn(Optional.of(potentialCustomer));
        when(categoryRepository.findByCategoryStatus("active")).thenReturn(Collections.singletonList(regularCategory));
        when(customerCategoryRepository.findByCustomerId("customer_test_001")).thenReturn(
                Collections.singletonList(
                        new com.crm.entity.CustomerCategory(1L, "customer_test_001", "category_regular")
                )
        );

        categoryMatchingWorker.processCustomerCategoriesAsync("customer_test_001");
        
        Thread.sleep(1500);
        
        verify(customerCategoryRepository, never()).save(any());
    }

    @Test
    @DisplayName("客户不存在时触发重试机制")
    void testProcessCustomerCategories_CustomerNotFound() throws InterruptedException {
        when(customerRepository.findByCustomerId("customer_nonexistent"))
                .thenReturn(Optional.empty());

        categoryMatchingWorker.processCustomerCategoriesAsync("customer_nonexistent");
        
        Thread.sleep(7000);
        
        assertEquals(1, categoryMatchingWorker.getFailedCount());
    }

    @Test
    @DisplayName("计数器重置功能")
    void testResetCounters() {
        assertEquals(0, categoryMatchingWorker.getProcessedCount());
        assertEquals(0, categoryMatchingWorker.getFailedCount());
        assertEquals(0, categoryMatchingWorker.getRetryCount());
    }

    @Test
    @DisplayName("后台Worker处理统计")
    void testWorkerStatistics() throws InterruptedException {
        when(customerRepository.findByCustomerId("customer_test_001")).thenReturn(Optional.of(potentialCustomer));
        when(categoryRepository.findByCategoryStatus("active")).thenReturn(Collections.emptyList());

        categoryMatchingWorker.processCustomerCategoriesAsync("customer_test_001");
        
        Thread.sleep(1500);
        
        assertEquals(1, categoryMatchingWorker.getProcessedCount());
        assertEquals(0, categoryMatchingWorker.getFailedCount());
        assertEquals(0, categoryMatchingWorker.getRetryCount());
    }
}
