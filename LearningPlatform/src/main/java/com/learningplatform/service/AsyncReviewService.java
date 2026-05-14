
package com.learningplatform.service;

import com.learningplatform.config.ReviewQueueConfig;
import com.learningplatform.dto.ReviewTask;
import com.learningplatform.entity.Review;
import com.learningplatform.repository.ReviewRepository;
import com.learningplatform.util.IdGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

@Service
public class AsyncReviewService {

    private static final Logger logger = LoggerFactory.getLogger(AsyncReviewService.class);

    @Autowired
    private ReviewRepository reviewRepository;

    @Autowired
    private HistoryService historyService;

    @Autowired
    private AnalysisService analysisService;

    @Autowired
    private ReviewService reviewService;

    @Autowired
    private RedisReviewQueueService queueService;

    @Autowired
    private ReviewQueueConfig queueConfig;

    public static class AsyncReviewResult {
        private final String taskId;
        private final String courseId;
        private final String studentId;
        private final boolean submittedImmediately;

        public AsyncReviewResult(String taskId, String courseId, String studentId, boolean submittedImmediately) {
            this.taskId = taskId;
            this.courseId = courseId;
            this.studentId = studentId;
            this.submittedImmediately = submittedImmediately;
        }

        public String getTaskId() { return taskId; }
        public String getCourseId() { return courseId; }
        public String getStudentId() { return studentId; }
        public boolean isSubmittedImmediately() { return submittedImmediately; }
    }

    public AsyncReviewResult submitReviewAsync(String courseId, String studentId, Integer rating, String content) {
        String taskId = queueService.enqueueTask(courseId, studentId, rating, content);
        logger.info("异步评价任务已提交到队列: task={}, course={}, student={}", taskId, courseId, studentId);
        return new AsyncReviewResult(taskId, courseId, studentId, false);
    }

    @Async
    public CompletableFuture<Review> processReviewAsync(String taskId, String courseId, String studentId, 
                                                          Integer rating, String content) {
        logger.info("开始异步处理评价: task={}", taskId);
        
        try {
            ReviewTask task = queueService.getTask(taskId);
            if (task == null) {
                logger.warn("任务不存在，直接处理: task={}", taskId);
            }

            Review savedReview = attemptSubmitWithRetry(courseId, studentId, rating, content);
            
            if (savedReview != null) {
                logger.info("异步评价处理完成: task={}, review={}", taskId, savedReview.getReviewId());
                if (task != null) {
                    queueService.completeTask(taskId);
                }
                return CompletableFuture.completedFuture(savedReview);
            } else {
                logger.warn("异步评价处理失败，已达最大重试次数: task={}", taskId);
                return CompletableFuture.completedFuture(null);
            }
        } catch (Exception e) {
            logger.error("异步评价处理异常: task={}", taskId, e);
            return CompletableFuture.completedFuture(null);
        }
    }

    private Review attemptSubmitWithRetry(String courseId, String studentId, 
                                           Integer rating, String content) throws InterruptedException {
        int attempts = 0;
        
        while (attempts < queueConfig.getMaxRetryAttempts()) {
            try {
                attempts++;
                logger.debug("尝试提交评价: course={}, student={}, attempt={}", 
                        courseId, studentId, attempts);

                Review existing = reviewRepository.findByCourseIdAndStudentId(courseId, studentId).orElse(null);
                Review review;
                
                if (existing != null) {
                    existing.setReviewRating(rating);
                    if (content != null) {
                        existing.setReviewContent(content);
                    }
                    review = reviewRepository.save(existing);
                } else {
                    review = new Review();
                    review.setReviewId(IdGenerator.generateReviewId());
                    review.setCourseId(courseId);
                    review.setStudentId(studentId);
                    review.setReviewRating(rating);
                    review.setReviewContent(content);
                    review.setReviewStatus("published");
                    review = reviewRepository.save(review);
                    
                    historyService.recordReviewSubmit(studentId, courseId, review.getReviewId(), rating);
                    analysisService.incrementReviewCount();
                }

                analysisService.updateAverageRating(courseId);
                return review;

            } catch (Exception e) {
                logger.warn("提交评价失败: course={}, student={}, attempt={}, error={}", 
                        courseId, studentId, attempts, e.getMessage());
                
                if (attempts < queueConfig.getMaxRetryAttempts()) {
                    long delay = queueConfig.getRetryDelayMs() * attempts;
                    logger.debug("等待重试: delay={}ms", delay);
                    Thread.sleep(delay);
                }
            }
        }
        
        return null;
    }

    public int getRetryCount(String taskId) {
        return queueService.getTaskRetryCount(taskId);
    }

    public Optional<ReviewTask> getPendingReview(String taskId) {
        ReviewTask task = queueService.getTask(taskId);
        return Optional.ofNullable(task);
    }

    public int getPendingReviewCount() {
        return (int) queueService.getPendingTaskCount();
    }

    public List<String> getPendingTaskIds() {
        return queueService.getPendingTaskIds();
    }

    @Transactional
    public Review retryFailedReview(String taskId) {
        ReviewTask task = queueService.getTask(taskId);
        if (task == null) {
            logger.warn("重试任务不存在: task={}", taskId);
            return null;
        }

        logger.info("手动重试评价任务: task={}", taskId);

        try {
            Review review = attemptSubmitWithRetry(
                    task.getCourseId(),
                    task.getStudentId(),
                    task.getRating(),
                    task.getContent()
            );

            if (review != null) {
                queueService.completeTask(taskId);
                logger.info("手动重试成功: task={}, review={}", taskId, review.getReviewId());
                return review;
            } else {
                logger.warn("手动重试失败: task={}", taskId);
                return null;
            }
        } catch (Exception e) {
            logger.error("手动重试异常: task={}", taskId, e);
            return null;
        }
    }

    public long getProcessingTaskCount() {
        return queueService.getProcessingTaskCount();
    }

    public long getRetryTaskCount() {
        return queueService.getRetryTaskCount();
    }

    public long getDeadLetterTaskCount() {
        return queueService.getDeadLetterTaskCount();
    }

    @Async
    public CompletableFuture<List<Review>> batchCollectReviews(List<Map<String, Object>> reviewDataList) {
        List<Review> results = new ArrayList<>();
        
        for (Map<String, Object> data : reviewDataList) {
            String courseId = (String) data.get("courseId");
            String studentId = (String) data.get("studentId");
            Integer rating = (Integer) data.get("rating");
            String content = (String) data.get("content");

            String taskId = queueService.enqueueTask(courseId, studentId, rating, content);
            
            try {
                Review review = processReviewAsync(
                    taskId, 
                    courseId, 
                    studentId, 
                    rating, 
                    content
                ).get();
                if (review != null) {
                    results.add(review);
                }
            } catch (Exception e) {
                logger.error("批量评价处理失败: task={}", taskId, e);
            }
        }
        return CompletableFuture.completedFuture(results);
    }
}
