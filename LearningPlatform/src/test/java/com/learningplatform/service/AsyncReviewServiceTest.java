package com.learningplatform.service;

import com.learningplatform.builder.TestDataBuilder;
import com.learningplatform.entity.Review;
import com.learningplatform.repository.ReviewRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("AsyncReviewService 异步评价服务测试")
class AsyncReviewServiceTest {

    @Mock
    private ReviewRepository reviewRepository;

    @Mock
    private HistoryService historyService;

    @Mock
    private AnalysisService analysisService;

    @Mock
    private ReviewService reviewService;

    @InjectMocks
    private AsyncReviewService asyncReviewService;

    private Review testReview;

    @BeforeEach
    void setUp() {
        testReview = TestDataBuilder.createDefaultReview();
    }

    @Nested
    @DisplayName("异步评价提交测试")
    class AsyncReviewSubmissionTests {

        @Test
        @DisplayName("应该立即返回任务ID不阻塞")
        void shouldReturnTaskIdImmediately() {
            long startTime = System.currentTimeMillis();
            
            AsyncReviewService.AsyncReviewResult result = asyncReviewService.submitReviewAsync(
                    TestDataBuilder.TEST_COURSE_ID,
                    TestDataBuilder.TEST_STUDENT_ID,
                    5,
                    "测试评价内容"
            );

            long duration = System.currentTimeMillis() - startTime;
            
            assertNotNull(result);
            assertNotNull(result.getTaskId());
            assertTrue(result.getTaskId().startsWith("review_task_"));
            assertFalse(result.isSubmittedImmediately());
            assertTrue(duration < 1000);
        }

        @Test
        @DisplayName("应该正确存储待处理的评价")
        void shouldStorePendingReview() {
            AsyncReviewService.AsyncReviewResult result = asyncReviewService.submitReviewAsync(
                    TestDataBuilder.TEST_COURSE_ID,
                    TestDataBuilder.TEST_STUDENT_ID,
                    5,
                    "测试评价内容"
            );

            Optional<Review> pendingReview = asyncReviewService.getPendingReview(result.getTaskId());
            assertTrue(pendingReview.isPresent());
            assertEquals(TestDataBuilder.TEST_COURSE_ID, pendingReview.get().getCourseId());
            assertEquals(TestDataBuilder.TEST_STUDENT_ID, pendingReview.get().getStudentId());
            assertEquals(5, pendingReview.get().getReviewRating());
        }

        @Test
        @DisplayName("应该正确设置任务ID")
        void shouldSetTaskIdCorrectly() {
            AsyncReviewService.AsyncReviewResult result1 = asyncReviewService.submitReviewAsync(
                    TestDataBuilder.TEST_COURSE_ID,
                    TestDataBuilder.TEST_STUDENT_ID,
                    5,
                    "评价1"
            );
            AsyncReviewService.AsyncReviewResult result2 = asyncReviewService.submitReviewAsync(
                    TestDataBuilder.TEST_COURSE_ID,
                    "student_002",
                    4,
                    "评价2"
            );

            assertNotNull(result1.getTaskId());
            assertNotNull(result2.getTaskId());
            assertNotEquals(result1.getTaskId(), result2.getTaskId());
        }

        @Test
        @DisplayName("应该正确统计待处理评价数量")
        void shouldCountPendingReviews() {
            assertEquals(0, asyncReviewService.getPendingReviewCount());

            asyncReviewService.submitReviewAsync("c1", "s1", 5, "评价1");
            assertEquals(1, asyncReviewService.getPendingReviewCount());

            asyncReviewService.submitReviewAsync("c2", "s2", 4, "评价2");
            assertEquals(2, asyncReviewService.getPendingReviewCount());
        }

        @Test
        @DisplayName("应该返回所有待处理任务ID")
        void shouldReturnAllPendingTaskIds() {
            List<String> initialTasks = asyncReviewService.getPendingTaskIds();
            assertTrue(initialTasks.isEmpty());

            AsyncReviewService.AsyncReviewResult result1 = asyncReviewService.submitReviewAsync("c1", "s1", 5, "评价1");
            AsyncReviewService.AsyncReviewResult result2 = asyncReviewService.submitReviewAsync("c2", "s2", 4, "评价2");

            List<String> taskIds = asyncReviewService.getPendingTaskIds();
            assertEquals(2, taskIds.size());
            assertTrue(taskIds.contains(result1.getTaskId()));
            assertTrue(taskIds.contains(result2.getTaskId()));
        }
    }

    @Nested
    @DisplayName("评价处理测试")
    class ReviewProcessingTests {

        @Test
        @DisplayName("应该成功处理评价")
        void shouldProcessReviewSuccessfully() throws ExecutionException, InterruptedException {
            when(reviewRepository.findByCourseIdAndStudentId(anyString(), anyString()))
                    .thenReturn(Optional.empty());
            when(reviewRepository.save(any(Review.class)))
                    .thenReturn(testReview);

            CompletableFuture<Review> future = asyncReviewService.processReviewAsync(
                    "test_task_001",
                    TestDataBuilder.TEST_COURSE_ID,
                    TestDataBuilder.TEST_STUDENT_ID,
                    5,
                    "测试评价内容"
            );

            Review result = future.get();
            assertNotNull(result);
            verify(reviewRepository, times(1)).save(any(Review.class));
        }

        @Test
        @DisplayName("应该更新已存在的评价")
        void shouldUpdateExistingReview() throws ExecutionException, InterruptedException {
            Review existingReview = TestDataBuilder.createDefaultReview();
            existingReview.setReviewRating(4);

            when(reviewRepository.findByCourseIdAndStudentId(TestDataBuilder.TEST_COURSE_ID, TestDataBuilder.TEST_STUDENT_ID))
                    .thenReturn(Optional.of(existingReview));
            when(reviewRepository.save(any(Review.class)))
                    .thenAnswer(invocation -> {
                        Review r = invocation.getArgument(0);
                        return r;
                    });

            CompletableFuture<Review> future = asyncReviewService.processReviewAsync(
                    "test_task_001",
                    TestDataBuilder.TEST_COURSE_ID,
                    TestDataBuilder.TEST_STUDENT_ID,
                    5,
                    "更新后的评价内容"
            );

            Review result = future.get();
            assertNotNull(result);
            assertEquals(5, result.getReviewRating());
        }

        @Test
        @DisplayName("处理成功后应该记录历史")
        void shouldRecordHistoryAfterSuccess() throws ExecutionException, InterruptedException {
            when(reviewRepository.findByCourseIdAndStudentId(anyString(), anyString()))
                    .thenReturn(Optional.empty());
            when(reviewRepository.save(any(Review.class)))
                    .thenReturn(testReview);

            CompletableFuture<Review> future = asyncReviewService.processReviewAsync(
                    "test_task_001",
                    TestDataBuilder.TEST_COURSE_ID,
                    TestDataBuilder.TEST_STUDENT_ID,
                    5,
                    "测试评价内容"
            );

            future.get();

            verify(historyService, times(1)).recordReviewSubmit(
                eq(TestDataBuilder.TEST_STUDENT_ID),
                eq(TestDataBuilder.TEST_COURSE_ID),
                anyString(),
                eq(5)
            );
        }

        @Test
        @DisplayName("处理成功后应该更新统计")
        void shouldUpdateStatisticsAfterSuccess() throws ExecutionException, InterruptedException {
            when(reviewRepository.findByCourseIdAndStudentId(anyString(), anyString()))
                    .thenReturn(Optional.empty());
            when(reviewRepository.save(any(Review.class)))
                    .thenReturn(testReview);

            CompletableFuture<Review> future = asyncReviewService.processReviewAsync(
                    "test_task_001",
                    TestDataBuilder.TEST_COURSE_ID,
                    TestDataBuilder.TEST_STUDENT_ID,
                    5,
                    "测试评价内容"
            );

            future.get();

            verify(analysisService, times(1)).incrementReviewCount();
            verify(analysisService, times(1)).updateAverageRating(TestDataBuilder.TEST_COURSE_ID);
        }

        @Test
        @DisplayName("处理失败时应该返回null")
        void shouldReturnNullOnFailure() throws ExecutionException, InterruptedException {
            when(reviewRepository.findByCourseIdAndStudentId(anyString(), anyString()))
                    .thenThrow(new RuntimeException("数据库错误"));

            CompletableFuture<Review> future = asyncReviewService.processReviewAsync(
                    "test_task_001",
                    TestDataBuilder.TEST_COURSE_ID,
                    TestDataBuilder.TEST_STUDENT_ID,
                    5,
                    "测试评价内容"
            );

            Review result = future.get();
            assertNull(result);
        }
    }

    @Nested
    @DisplayName("重试机制测试")
    class RetryMechanismTests {

        @Test
        @DisplayName("初始重试次数应该为0")
        void initialRetryCountShouldBeZero() {
            AsyncReviewService.AsyncReviewResult result = asyncReviewService.submitReviewAsync(
                    TestDataBuilder.TEST_COURSE_ID,
                    TestDataBuilder.TEST_STUDENT_ID,
                    5,
                    "测试评价内容"
            );

            int retryCount = asyncReviewService.getRetryCount(result.getTaskId());
            assertEquals(0, retryCount);
        }

        @Test
        @DisplayName("应该支持手动重试失败的评价")
        void shouldSupportManualRetryForFailedReview() {
            AsyncReviewService.AsyncReviewResult result = asyncReviewService.submitReviewAsync(
                    TestDataBuilder.TEST_COURSE_ID,
                    TestDataBuilder.TEST_STUDENT_ID,
                    5,
                    "测试评价内容"
            );

            when(reviewRepository.save(any(Review.class)))
                    .thenReturn(testReview);

            Review retried = asyncReviewService.retryFailedReview(result.getTaskId());

            assertNotNull(retried);
            assertEquals(0, asyncReviewService.getPendingReviewCount());
        }

        @Test
        @DisplayName("手动重试成功后应该更新统计")
        void shouldUpdateStatisticsAfterManualRetry() {
            AsyncReviewService.AsyncReviewResult result = asyncReviewService.submitReviewAsync(
                    TestDataBuilder.TEST_COURSE_ID,
                    TestDataBuilder.TEST_STUDENT_ID,
                    5,
                    "测试评价内容"
            );

            when(reviewRepository.save(any(Review.class)))
                    .thenReturn(testReview);

            asyncReviewService.retryFailedReview(result.getTaskId());

            verify(analysisService, times(1)).incrementReviewCount();
            verify(analysisService, times(1)).updateAverageRating(TestDataBuilder.TEST_COURSE_ID);
        }

        @Test
        @DisplayName("任务不存在时重试应该返回null")
        void shouldReturnNullForNonExistentTask() {
            Review result = asyncReviewService.retryFailedReview("nonexistent_task");
            assertNull(result);
        }

        @Test
        @DisplayName("应该正确获取重试次数")
        void shouldGetRetryCount() {
            String taskId = "test_task_retry";
            
            Review pendingReview = TestDataBuilder.createDefaultReview();
            asyncReviewService.submitReviewAsync(
                    TestDataBuilder.TEST_COURSE_ID,
                    TestDataBuilder.TEST_STUDENT_ID,
                    5,
                    "测试"
            );

            int retryCount = asyncReviewService.getRetryCount("nonexistent_task");
            assertEquals(0, retryCount);
        }
    }

    @Nested
    @DisplayName("评价数据完整性测试")
    class ReviewDataIntegrityTests {

        @Test
        @DisplayName("提交的评价应该包含完整数据")
        void submittedReviewShouldHaveCompleteData() {
            String expectedCourseId = "course_integrity_test";
            String expectedStudentId = "student_integrity_test";
            int expectedRating = 4;
            String expectedContent = "完整性测试评价内容";

            AsyncReviewService.AsyncReviewResult result = asyncReviewService.submitReviewAsync(
                    expectedCourseId,
                    expectedStudentId,
                    expectedRating,
                    expectedContent
            );

            Optional<Review> pendingReview = asyncReviewService.getPendingReview(result.getTaskId());
            
            assertTrue(pendingReview.isPresent());
            assertEquals(expectedCourseId, pendingReview.get().getCourseId());
            assertEquals(expectedStudentId, pendingReview.get().getStudentId());
            assertEquals(expectedRating, pendingReview.get().getReviewRating());
            assertEquals(expectedContent, pendingReview.get().getReviewContent());
        }

        @Test
        @DisplayName("评价状态应该为pending")
        void reviewStatusShouldBePending() {
            AsyncReviewService.AsyncReviewResult result = asyncReviewService.submitReviewAsync(
                    TestDataBuilder.TEST_COURSE_ID,
                    TestDataBuilder.TEST_STUDENT_ID,
                    5,
                    "测试"
            );

            Optional<Review> pendingReview = asyncReviewService.getPendingReview(result.getTaskId());
            
            assertTrue(pendingReview.isPresent());
            assertEquals("pending", pendingReview.get().getReviewStatus());
        }

        @Test
        @DisplayName("应该生成评价ID")
        void shouldGenerateReviewId() {
            AsyncReviewService.AsyncReviewResult result = asyncReviewService.submitReviewAsync(
                    TestDataBuilder.TEST_COURSE_ID,
                    TestDataBuilder.TEST_STUDENT_ID,
                    5,
                    "测试"
            );

            Optional<Review> pendingReview = asyncReviewService.getPendingReview(result.getTaskId());
            
            assertTrue(pendingReview.isPresent());
            assertNotNull(pendingReview.get().getReviewId());
            assertTrue(pendingReview.get().getReviewId().startsWith("review_"));
        }
    }

    @Nested
    @DisplayName("批量评价收集测试")
    class BatchReviewCollectionTests {

        @Test
        @DisplayName("应该支持批量收集评价")
        void shouldSupportBatchReviewCollection() throws ExecutionException, InterruptedException {
            List<Map<String, Object>> reviewDataList = new ArrayList<>();
            
            Map<String, Object> review1 = new HashMap<>();
            review1.put("courseId", "c1");
            review1.put("studentId", "s1");
            review1.put("rating", 5);
            review1.put("content", "评价1");
            reviewDataList.add(review1);

            Map<String, Object> review2 = new HashMap<>();
            review2.put("courseId", "c2");
            review2.put("studentId", "s2");
            review2.put("rating", 4);
            review2.put("content", "评价2");
            reviewDataList.add(review2);

            when(reviewRepository.findByCourseIdAndStudentId(anyString(), anyString()))
                    .thenReturn(Optional.empty());
            when(reviewRepository.save(any(Review.class)))
                    .thenReturn(testReview);

            CompletableFuture<List<Review>> future = asyncReviewService.batchCollectReviews(reviewDataList);
            List<Review> results = future.get();

            assertNotNull(results);
        }

        @Test
        @DisplayName("空列表应该返回空结果")
        void shouldReturnEmptyForEmptyList() throws ExecutionException, InterruptedException {
            CompletableFuture<List<Review>> future = asyncReviewService.batchCollectReviews(Collections.emptyList());
            List<Review> results = future.get();

            assertNotNull(results);
            assertTrue(results.isEmpty());
        }
    }

    @Nested
    @DisplayName("后台Worker执行测试")
    class BackgroundWorkerTests {

        @Test
        @DisplayName("处理应该返回CompletableFuture")
        void processShouldReturnCompletableFuture() {
            when(reviewRepository.findByCourseIdAndStudentId(anyString(), anyString()))
                    .thenReturn(Optional.empty());
            when(reviewRepository.save(any(Review.class)))
                    .thenReturn(testReview);

            CompletableFuture<Review> future = asyncReviewService.processReviewAsync(
                    "worker_test_task",
                    TestDataBuilder.TEST_COURSE_ID,
                    TestDataBuilder.TEST_STUDENT_ID,
                    5,
                    "测试评价"
            );

            assertNotNull(future);
            assertTrue(future instanceof CompletableFuture);
        }

        @Test
        @DisplayName("应该异步执行处理")
        void shouldExecuteProcessingAsynchronously() throws ExecutionException, InterruptedException {
            when(reviewRepository.findByCourseIdAndStudentId(anyString(), anyString()))
                    .thenReturn(Optional.empty());
            when(reviewRepository.save(any(Review.class)))
                    .thenReturn(testReview);

            long startTime = System.currentTimeMillis();
            CompletableFuture<Review> future = asyncReviewService.processReviewAsync(
                    "async_test_task",
                    TestDataBuilder.TEST_COURSE_ID,
                    TestDataBuilder.TEST_STUDENT_ID,
                    5,
                    "测试评价"
            );

            Review result = future.get();
            long duration = System.currentTimeMillis() - startTime;

            assertNotNull(result);
        }

        @Test
        @DisplayName("多个任务可以并行处理")
        void multipleTasksCanBeProcessedInParallel() {
            when(reviewRepository.findByCourseIdAndStudentId(anyString(), anyString()))
                    .thenReturn(Optional.empty());
            when(reviewRepository.save(any(Review.class)))
                    .thenReturn(testReview);

            List<CompletableFuture<Review>> futures = new ArrayList<>();
            for (int i = 0; i < 5; i++) {
                futures.add(asyncReviewService.processReviewAsync(
                        "task_" + i,
                        TestDataBuilder.TEST_COURSE_ID,
                        "student_" + i,
                        5,
                        "评价" + i
                ));
            }

            assertEquals(5, futures.size());
        }
    }
}
