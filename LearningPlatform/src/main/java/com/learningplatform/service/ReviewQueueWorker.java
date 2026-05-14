
package com.learningplatform.service;

import com.learningplatform.config.ReviewQueueConfig;
import com.learningplatform.dto.ReviewTask;
import com.learningplatform.entity.Review;
import com.learningplatform.repository.ReviewRepository;
import com.learningplatform.util.IdGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;

@Component
public class ReviewQueueWorker {

    private static final Logger logger = LoggerFactory.getLogger(ReviewQueueWorker.class);

    @Autowired
    private RedisReviewQueueService queueService;

    @Autowired
    private ReviewRepository reviewRepository;

    @Autowired
    private HistoryService historyService;

    @Autowired
    private AnalysisService analysisService;

    @Autowired
    private ReviewQueueConfig queueConfig;

    private volatile boolean running = false;

    @PostConstruct
    public void init() {
        if (queueConfig.getWorker().isEnabled()) {
            running = true;
            logger.info("评价队列 Worker 已启动");
        }
    }

    @Scheduled(fixedDelayString = "${learning.review.worker.poll-interval-ms:1000}")
    public void processQueue() {
        if (!running) {
            return;
        }

        queueService.moveRetryTasksToMainQueue();

        int batchSize = queueConfig.getWorker().getBatchSize();
        int processedCount = 0;

        for (int i = 0; i < batchSize; i++) {
            ReviewTask task = queueService.dequeueTask();
            if (task == null) {
                break;
            }

            try {
                processTask(task);
                queueService.completeTask(task.getTaskId());
                processedCount++;
            } catch (Exception e) {
                logger.error("处理评价任务失败: task={}, error={}", task.getTaskId(), e.getMessage(), e);
                queueService.failTask(task.getTaskId(), e.getMessage());
            }
        }

        if (processedCount > 0) {
            logger.debug("本轮处理评价任务: count={}", processedCount);
        }
    }

    private void processTask(ReviewTask task) {
        String courseId = task.getCourseId();
        String studentId = task.getStudentId();
        Integer rating = task.getRating();
        String content = task.getContent();

        logger.info("Worker 处理评价任务: task={}, course={}, student={}", 
                task.getTaskId(), courseId, studentId);

        Review existing = reviewRepository.findByCourseIdAndStudentId(courseId, studentId).orElse(null);
        Review review;

        if (existing != null) {
            existing.setReviewRating(rating);
            if (content != null) {
                existing.setReviewContent(content);
            }
            review = reviewRepository.save(existing);
            logger.info("更新已存在的评价: review={}", review.getReviewId());
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
            logger.info("创建新评价: review={}", review.getReviewId());
        }

        analysisService.updateAverageRating(courseId);
        logger.info("评价任务处理完成: task={}", task.getTaskId());
    }

    public void start() {
        if (!running) {
            running = true;
            logger.info("评价队列 Worker 已启动");
        }
    }

    public void stop() {
        if (running) {
            running = false;
            logger.info("评价队列 Worker 已停止");
        }
    }

    public boolean isRunning() {
        return running;
    }
}
