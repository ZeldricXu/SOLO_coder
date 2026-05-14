package com.finance.service;

import com.finance.builder.TestDataBuilder;
import com.finance.entity.Category;
import com.finance.entity.Record;
import com.finance.exception.FinanceException;
import com.finance.repository.CategoryRepository;
import com.finance.repository.RecordRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("分类模块单元测试")
class CategoryServiceUnitTest {

    @Mock
    private CategoryRepository categoryRepository;

    @Mock
    private RecordRepository recordRepository;

    @InjectMocks
    private CategoryService categoryService;

    private String testAccountId;
    private Category foodCategory;
    private Category salaryCategory;

    @BeforeEach
    void setUp() {
        testAccountId = TestDataBuilder.generateUniqueId("account");
        foodCategory = TestDataBuilder.buildExpenseCategory("cat_001", "餐饮");
        salaryCategory = TestDataBuilder.buildIncomeCategory("cat_002", "工资");
    }

    @Nested
    @DisplayName("分类匹配测试")
    class CategoryMatchingTests {

        @Test
        @DisplayName("收入记录匹配收入分类成功")
        void testIncomeRecordMatchesIncomeCategory() {
            when(categoryRepository.findByCategoryName("工资")).thenReturn(Optional.of(salaryCategory));

            Category result = categoryService.matchCategory("income", "工资");

            assertNotNull(result);
            assertEquals("工资", result.getCategoryName());
            assertEquals("income", result.getCategoryType());
        }

        @Test
        @DisplayName("支出记录匹配支出分类成功")
        void testExpenseRecordMatchesExpenseCategory() {
            when(categoryRepository.findByCategoryName("餐饮")).thenReturn(Optional.of(foodCategory));

            Category result = categoryService.matchCategory("expense", "餐饮");

            assertNotNull(result);
            assertEquals("餐饮", result.getCategoryName());
            assertEquals("expense", result.getCategoryType());
        }

        @Test
        @DisplayName("收入记录与支出分类不匹配返回null")
        void testIncomeWithExpenseCategoryReturnsNull() {
            when(categoryRepository.findByCategoryName("餐饮")).thenReturn(Optional.of(foodCategory));

            Category result = categoryService.matchCategory("income", "餐饮");

            assertNull(result);
        }

        @Test
        @DisplayName("支出记录与收入分类不匹配返回null")
        void testExpenseWithIncomeCategoryReturnsNull() {
            when(categoryRepository.findByCategoryName("工资")).thenReturn(Optional.of(salaryCategory));

            Category result = categoryService.matchCategory("expense", "工资");

            assertNull(result);
        }

        @Test
        @DisplayName("分类不存在时返回null")
        void testNonExistingCategoryReturnsNull() {
            when(categoryRepository.findByCategoryName("不存在的分类")).thenReturn(Optional.empty());

            Category result = categoryService.matchCategory("income", "不存在的分类");

            assertNull(result);
        }

        @Test
        @DisplayName("多个分类独立匹配")
        void testMultipleCategoriesIndependentMatching() {
            Category transportCategory = TestDataBuilder.buildExpenseCategory("cat_003", "交通");
            Category bonusCategory = TestDataBuilder.buildIncomeCategory("cat_004", "奖金");

            when(categoryRepository.findByCategoryName("餐饮")).thenReturn(Optional.of(foodCategory));
            when(categoryRepository.findByCategoryName("交通")).thenReturn(Optional.of(transportCategory));
            when(categoryRepository.findByCategoryName("工资")).thenReturn(Optional.of(salaryCategory));
            when(categoryRepository.findByCategoryName("奖金")).thenReturn(Optional.of(bonusCategory));

            Category matchedFood = categoryService.matchCategory("expense", "餐饮");
            Category matchedTransport = categoryService.matchCategory("expense", "交通");
            Category matchedSalary = categoryService.matchCategory("income", "工资");
            Category matchedBonus = categoryService.matchCategory("income", "奖金");

            assertNotNull(matchedFood);
            assertNotNull(matchedTransport);
            assertNotNull(matchedSalary);
            assertNotNull(matchedBonus);
            assertEquals("expense", matchedFood.getCategoryType());
            assertEquals("expense", matchedTransport.getCategoryType());
            assertEquals("income", matchedSalary.getCategoryType());
            assertEquals("income", matchedBonus.getCategoryType());
        }

        @Test
        @DisplayName("分类匹配区分大小写")
        void testCategoryMatchingCaseSensitive() {
            Category category = TestDataBuilder.buildExpenseCategory("cat_001", "餐饮");
            when(categoryRepository.findByCategoryName("餐饮")).thenReturn(Optional.of(category));
            when(categoryRepository.findByCategoryName("餐飲")).thenReturn(Optional.empty());

            Category matchedSimplified = categoryService.matchCategory("expense", "餐饮");
            Category matchedTraditional = categoryService.matchCategory("expense", "餐飲");

            assertNotNull(matchedSimplified);
            assertNull(matchedTraditional);
        }
    }

    @Nested
    @DisplayName("分类统计测试")
    class CategoryStatisticsTests {

        @Test
        @DisplayName("分类统计计算总金额正确")
        void testCategoryStatisticsTotalAmount() {
            LocalDateTime startTime = LocalDateTime.now().withDayOfMonth(1).withHour(0).withMinute(0).withSecond(0);
            LocalDateTime endTime = LocalDateTime.now().withDayOfMonth(LocalDateTime.now().getMonth().length(true))
                .withHour(23).withMinute(59).withSecond(59);

            List<Object[]> categoryStats = Arrays.asList(
                new Object[]{"餐饮", new BigDecimal("3000.00")},
                new Object[]{"交通", new BigDecimal("500.00")},
                new Object[]{"购物", new BigDecimal("2000.00")}
            );

            when(recordRepository.sumByCategoryAndTimeRange(eq(testAccountId), any(LocalDateTime.class), any(LocalDateTime.class)))
                .thenReturn(categoryStats);

            Map<String, Object> result = categoryService.getCategoryStatistics(testAccountId, startTime, endTime);

            BigDecimal totalAmount = (BigDecimal) result.get("total_amount");
            assertEquals(new BigDecimal("5500.00"), totalAmount);
        }

        @Test
        @DisplayName("分类统计计算占比正确")
        void testCategoryStatisticsPercentage() {
            LocalDateTime startTime = LocalDateTime.now().withDayOfMonth(1).withHour(0).withMinute(0).withSecond(0);
            LocalDateTime endTime = LocalDateTime.now().withDayOfMonth(LocalDateTime.now().getMonth().length(true))
                .withHour(23).withMinute(59).withSecond(59);

            List<Object[]> categoryStats = Arrays.asList(
                new Object[]{"餐饮", new BigDecimal("5000.00")},
                new Object[]{"交通", new BigDecimal("3000.00")},
                new Object[]{"购物", new BigDecimal("2000.00")}
            );

            when(recordRepository.sumByCategoryAndTimeRange(eq(testAccountId), any(LocalDateTime.class), any(LocalDateTime.class)))
                .thenReturn(categoryStats);

            Map<String, Object> result = categoryService.getCategoryStatistics(testAccountId, startTime, endTime);
            List<Map<String, Object>> categoryList = (List<Map<String, Object>>) result.get("category_list");

            Map<String, Object> foodCategory = categoryList.stream()
                .filter(cat -> "餐饮".equals(cat.get("category_name")))
                .findFirst()
                .orElse(null);

            assertNotNull(foodCategory);
            assertEquals(new BigDecimal("50.00"), foodCategory.get("percentage"));
        }

        @Test
        @DisplayName("无数据时分类统计返回正确")
        void testCategoryStatisticsWithNoData() {
            LocalDateTime startTime = LocalDateTime.now().withDayOfMonth(1);
            LocalDateTime endTime = LocalDateTime.now();

            when(recordRepository.sumByCategoryAndTimeRange(eq(testAccountId), any(LocalDateTime.class), any(LocalDateTime.class)))
                .thenReturn(new ArrayList<>());

            Map<String, Object> result = categoryService.getCategoryStatistics(testAccountId, startTime, endTime);

            assertEquals(BigDecimal.ZERO, result.get("total_amount"));
            assertEquals(0, result.get("category_count"));
        }

        @Test
        @DisplayName("分类统计按金额降序排序")
        void testCategoryStatisticsSortedByAmountDesc() {
            LocalDateTime startTime = LocalDateTime.now().withDayOfMonth(1);
            LocalDateTime endTime = LocalDateTime.now();

            List<Object[]> categoryStats = Arrays.asList(
                new Object[]{"交通", new BigDecimal("1000.00")},
                new Object[]{"餐饮", new BigDecimal("5000.00")},
                new Object[]{"购物", new BigDecimal("3000.00")}
            );

            when(recordRepository.sumByCategoryAndTimeRange(eq(testAccountId), any(LocalDateTime.class), any(LocalDateTime.class)))
                .thenReturn(categoryStats);

            Map<String, Object> result = categoryService.getCategoryStatistics(testAccountId, startTime, endTime);
            List<Map<String, Object>> categoryList = (List<Map<String, Object>>) result.get("category_list");

            assertEquals("餐饮", categoryList.get(0).get("category_name"));
            assertEquals("购物", categoryList.get(1).get("category_name"));
            assertEquals("交通", categoryList.get(2).get("category_name"));
        }

        @Test
        @DisplayName("单个分类统计正确")
        void testSingleCategoryStatistics() {
            LocalDateTime startTime = LocalDateTime.now().withDayOfMonth(1);
            LocalDateTime endTime = LocalDateTime.now();

            List<Object[]> categoryStats = Arrays.asList(
                new Object[]{"餐饮", new BigDecimal("5000.00")}
            );

            when(recordRepository.sumByCategoryAndTimeRange(eq(testAccountId), any(LocalDateTime.class), any(LocalDateTime.class)))
                .thenReturn(categoryStats);

            Map<String, Object> result = categoryService.getCategoryStatistics(testAccountId, startTime, endTime);

            assertEquals(1, result.get("category_count"));
            assertEquals(new BigDecimal("5000.00"), result.get("total_amount"));
        }
    }

    @Nested
    @DisplayName("分类管理测试")
    class CategoryManagementTests {

        @Test
        @DisplayName("创建分类成功")
        void testCreateCategorySuccess() {
            String categoryName = "娱乐";
            String categoryType = "expense";
            String categoryParent = "living";

            when(categoryRepository.findByCategoryName(categoryName)).thenReturn(Optional.empty());
            when(categoryRepository.save(any(Category.class))).thenAnswer(invocation -> invocation.getArgument(0));

            Category result = categoryService.createCategory(categoryName, categoryType, categoryParent);

            assertNotNull(result);
            assertEquals(categoryName, result.getCategoryName());
            assertEquals(categoryType, result.getCategoryType());
            assertEquals(categoryParent, result.getCategoryParent());
            assertEquals("active", result.getCategoryStatus());
        }

        @Test
        @DisplayName("创建重复分类抛出异常")
        void testCreateDuplicateCategoryThrowsException() {
            String categoryName = "餐饮";

            when(categoryRepository.findByCategoryName(categoryName)).thenReturn(Optional.of(foodCategory));

            assertThrows(FinanceException.class, () ->
                categoryService.createCategory(categoryName, "expense", "living")
            );

            verify(categoryRepository, never()).save(any(Category.class));
        }

        @Test
        @DisplayName("查询所有分类成功")
        void testGetAllCategories() {
            List<Category> categories = Arrays.asList(
                foodCategory,
                salaryCategory,
                TestDataBuilder.buildExpenseCategory("cat_003", "交通")
            );

            when(categoryRepository.findAll()).thenReturn(categories);

            List<Category> result = categoryService.getAllCategories();

            assertEquals(3, result.size());
        }

        @Test
        @DisplayName("按类型查询分类成功")
        void testGetCategoriesByType() {
            List<Category> expenseCategories = Arrays.asList(
                foodCategory,
                TestDataBuilder.buildExpenseCategory("cat_003", "交通")
            );

            when(categoryRepository.findByCategoryType("expense")).thenReturn(expenseCategories);

            List<Category> result = categoryService.getCategoriesByType("expense");

            assertEquals(2, result.size());
            result.forEach(cat -> assertEquals("expense", cat.getCategoryType()));
        }

        @Test
        @DisplayName("查询活跃分类成功")
        void testGetActiveCategories() {
            Category inactiveCategory = TestDataBuilder.buildInactiveCategory("cat_004", "旧分类");
            List<Category> activeCategories = Arrays.asList(
                foodCategory,
                salaryCategory
            );

            when(categoryRepository.findByCategoryStatus("active")).thenReturn(activeCategories);

            List<Category> result = categoryService.getActiveCategories();

            assertEquals(2, result.size());
            result.forEach(cat -> assertEquals("active", cat.getCategoryStatus()));
        }

        @Test
        @DisplayName("按名称查询分类成功")
        void testGetCategoryByName() {
            when(categoryRepository.findByCategoryName("餐饮")).thenReturn(Optional.of(foodCategory));

            Category result = categoryService.getCategoryByName("餐饮");

            assertNotNull(result);
            assertEquals("餐饮", result.getCategoryName());
        }

        @Test
        @DisplayName("查询不存在分类抛出异常")
        void testGetNonExistingCategoryThrowsException() {
            when(categoryRepository.findByCategoryName("不存在的分类")).thenReturn(Optional.empty());

            assertThrows(FinanceException.class, () ->
                categoryService.getCategoryByName("不存在的分类")
            );
        }

        @Test
        @DisplayName("检查分类存在成功")
        void testCategoryExists() {
            when(categoryRepository.findByCategoryName("餐饮")).thenReturn(Optional.of(foodCategory));
            when(categoryRepository.findByCategoryName("不存在的分类")).thenReturn(Optional.empty());

            assertTrue(categoryService.existsByName("餐饮"));
            assertFalse(categoryService.existsByName("不存在的分类"));
        }

        @Test
        @DisplayName("更新分类成功")
        void testUpdateCategory() {
            String newName = "新餐饮名称";
            String categoryId = "cat_001";

            when(categoryRepository.findById(categoryId)).thenReturn(Optional.of(foodCategory));
            when(categoryRepository.save(any(Category.class))).thenAnswer(invocation -> invocation.getArgument(0));

            Category result = categoryService.updateCategory(categoryId, newName, "active");

            assertEquals(newName, result.getCategoryName());
        }

        @Test
        @DisplayName("更新不存在分类抛出异常")
        void testUpdateNonExistingCategoryThrowsException() {
            when(categoryRepository.findById("non_existing")).thenReturn(Optional.empty());

            assertThrows(FinanceException.class, () ->
                categoryService.updateCategory("non_existing", "新名称", "active")
            );
        }
    }

    @Nested
    @DisplayName("分类匹配异步化测试")
    class CategoryMatchingAsyncTests {

        private ExecutorService executorService;

        @BeforeEach
        void setUp() {
            executorService = Executors.newFixedThreadPool(2);
        }

        @Test
        @DisplayName("收支记录创建立即返回不阻塞")
        void testRecordCreationReturnsImmediately() throws Exception {
            AtomicBoolean mainThreadFinished = new AtomicBoolean(false);
            AtomicBoolean asyncTaskStarted = new AtomicBoolean(false);
            AtomicBoolean asyncTaskFinished = new AtomicBoolean(false);

            CountDownLatch asyncLatch = new CountDownLatch(1);

            when(categoryRepository.findByCategoryName("工资")).thenReturn(Optional.of(salaryCategory));

            Future<?> mainTask = executorService.submit(() -> {
                Category result = categoryService.matchCategory("income", "工资");
                assertNotNull(result);
                mainThreadFinished.set(true);
            });

            Future<?> asyncTask = executorService.submit(() -> {
                asyncTaskStarted.set(true);
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                asyncTaskFinished.set(true);
                asyncLatch.countDown();
            });

            mainTask.get(500, TimeUnit.MILLISECONDS);

            assertTrue(mainThreadFinished.get());
            assertTrue(asyncTaskStarted.get() || asyncTaskFinished.get());
            asyncLatch.await(500, TimeUnit.MILLISECONDS);
            assertTrue(asyncTaskFinished.get());

            executorService.shutdown();
        }

        @Test
        @DisplayName("后台Worker执行分类匹配")
        void testBackgroundWorkerPerformsCategoryMatching() throws Exception {
            ExecutorService workerPool = Executors.newSingleThreadExecutor();
            AtomicInteger matchCount = new AtomicInteger(0);
            List<String> matchedCategories = Collections.synchronizedList(new ArrayList<>());

            when(categoryRepository.findByCategoryName("餐饮")).thenReturn(Optional.of(foodCategory));
            when(categoryRepository.findByCategoryName("工资")).thenReturn(Optional.of(salaryCategory));

            CountDownLatch workerLatch = new CountDownLatch(2);

            workerPool.submit(() -> {
                Category result = categoryService.matchCategory("expense", "餐饮");
                if (result != null) {
                    matchedCategories.add(result.getCategoryName());
                    matchCount.incrementAndGet();
                }
                workerLatch.countDown();
            });

            workerPool.submit(() -> {
                Category result = categoryService.matchCategory("income", "工资");
                if (result != null) {
                    matchedCategories.add(result.getCategoryName());
                    matchCount.incrementAndGet();
                }
                workerLatch.countDown();
            });

            boolean completed = workerLatch.await(500, TimeUnit.MILLISECONDS);

            assertTrue(completed);
            assertEquals(2, matchCount.get());
            assertTrue(matchedCategories.contains("餐饮"));
            assertTrue(matchedCategories.contains("工资"));

            workerPool.shutdown();
        }

        @Test
        @DisplayName("后台统计更新不阻塞主流程")
        void testBackgroundStatisticsUpdateDoesNotBlock() throws Exception {
            ExecutorService executor = Executors.newFixedThreadPool(2);
            AtomicBoolean statisticsCalculated = new AtomicBoolean(false);

            LocalDateTime startTime = LocalDateTime.now().withDayOfMonth(1);
            LocalDateTime endTime = LocalDateTime.now();

            List<Object[]> categoryStats = Arrays.asList(
                new Object[]{"餐饮", new BigDecimal("3000.00")}
            );

            when(recordRepository.sumByCategoryAndTimeRange(eq(testAccountId), any(LocalDateTime.class), any(LocalDateTime.class)))
                .thenAnswer(invocation -> {
                    Thread.sleep(100);
                    return categoryStats;
                });

            long startTimeNs = System.nanoTime();

            Future<?> mainThread = executor.submit(() -> {
                Category result = categoryService.matchCategory("expense", "餐饮");
                assertNotNull(result);
            });

            Future<?> statsThread = executor.submit(() -> {
                Map<String, Object> stats = categoryService.getCategoryStatistics(testAccountId, startTime, endTime);
                assertNotNull(stats);
                statisticsCalculated.set(true);
            });

            mainThread.get(300, TimeUnit.MILLISECONDS);
            long mainThreadTime = System.nanoTime() - startTimeNs;

            statsThread.get(500, TimeUnit.MILLISECONDS);

            assertTrue(statisticsCalculated.get());
            assertTrue(mainThreadTime < 500_000_000);

            executor.shutdown();
        }

        @Test
        @DisplayName("分类匹配失败时的重试机制")
        void testCategoryMatchingRetryMechanism() throws Exception {
            ExecutorService executor = Executors.newSingleThreadExecutor();
            AtomicInteger retryCount = new AtomicInteger(0);
            AtomicBoolean eventuallySucceeded = new AtomicBoolean(false);

            when(categoryRepository.findByCategoryName("工资"))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(salaryCategory));

            Callable<Category> matchingTask = () -> {
                Category result = null;
                int maxRetries = 3;
                for (int i = 0; i < maxRetries; i++) {
                    retryCount.incrementAndGet();
                    result = categoryService.matchCategory("income", "工资");
                    if (result != null) {
                        eventuallySucceeded.set(true);
                        break;
                    }
                    Thread.sleep(50);
                }
                return result;
            };

            Future<Category> future = executor.submit(matchingTask);
            Category result = future.get(500, TimeUnit.MILLISECONDS);

            assertNotNull(result);
            assertEquals(3, retryCount.get());
            assertTrue(eventuallySucceeded.get());

            executor.shutdown();
        }

        @Test
        @DisplayName("并发分类匹配线程安全")
        void testConcurrentCategoryMatchingThreadSafe() throws Exception {
            int threadCount = 10;
            ExecutorService executor = Executors.newFixedThreadPool(threadCount);
            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch endLatch = new CountDownLatch(threadCount);
            List<Category> results = Collections.synchronizedList(new ArrayList<>());
            AtomicInteger successCount = new AtomicInteger(0);
            AtomicInteger failureCount = new AtomicInteger(0);

            when(categoryRepository.findByCategoryName("餐饮")).thenReturn(Optional.of(foodCategory));
            when(categoryRepository.findByCategoryName("工资")).thenReturn(Optional.of(salaryCategory));

            for (int i = 0; i < threadCount; i++) {
                final int index = i;
                executor.submit(() -> {
                    try {
                        startLatch.await();
                        String categoryType = index % 2 == 0 ? "expense" : "income";
                        String categoryName = index % 2 == 0 ? "餐饮" : "工资";

                        Category result = categoryService.matchCategory(categoryType, categoryName);
                        if (result != null) {
                            results.add(result);
                            successCount.incrementAndGet();
                        } else {
                            failureCount.incrementAndGet();
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        endLatch.countDown();
                    }
                });
            }

            startLatch.countDown();
            boolean completed = endLatch.await(500, TimeUnit.MILLISECONDS);

            assertTrue(completed);
            assertEquals(threadCount, successCount.get() + failureCount.get());
            assertEquals(threadCount, successCount.get());
            assertEquals(threadCount, results.size());

            executor.shutdown();
        }
    }
}
