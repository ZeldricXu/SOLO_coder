
package com.learningplatform.service;

import com.learningplatform.entity.Review;
import com.learningplatform.exception.BusinessException;
import com.learningplatform.repository.ReviewRepository;
import com.learningplatform.util.IdGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

@Service
public class ReviewService {

    private static final Logger logger = LoggerFactory.getLogger(ReviewService.class);

    @Autowired
    private ReviewRepository reviewRepository;

    @Autowired
    private CourseService courseService;

    @Autowired
    private StudentService studentService;

    @Autowired
    private HistoryService historyService;

    @Autowired
    private AnalysisService analysisService;

    @Transactional
    public Review submitReview(String courseId, String studentId, Integer rating, String content) {
        courseService.getCourseById(courseId);
        studentService.getStudentById(studentId);

        if (rating == null || rating < 1 || rating > 5) {
            throw new BusinessException(400, "评分必须在1-5之间");
        }

        Optional<Review> existing = reviewRepository.findByCourseIdAndStudentId(courseId, studentId);
        if (existing.isPresent()) {
            Review review = existing.get();
            review.setReviewRating(rating);
            if (content != null) {
                review.setReviewContent(content);
            }
            Review saved = reviewRepository.save(review);
            logger.info("更新课程评价: review={}", saved.getReviewId());
            analysisService.updateAverageRating(courseId);
            return saved;
        }

        Review review = new Review();
        review.setReviewId(IdGenerator.generateReviewId());
        review.setCourseId(courseId);
        review.setStudentId(studentId);
        review.setReviewRating(rating);
        review.setReviewContent(content);
        review.setReviewStatus("published");

        Review saved = reviewRepository.save(review);

        historyService.recordReviewSubmit(studentId, courseId, saved.getReviewId(), rating);
        analysisService.incrementReviewCount();
        analysisService.updateAverageRating(courseId);

        logger.info("提交课程评价: review={}, course={}, student={}, rating={}", 
                saved.getReviewId(), courseId, studentId, rating);
        return saved;
    }

    public Review getReviewById(String reviewId) {
        return reviewRepository.findById(reviewId)
                .orElseThrow(() -> new BusinessException(404, "评价不存在: " + reviewId));
    }

    public Optional<Review> findReviewByCourseAndStudent(String courseId, String studentId) {
        return reviewRepository.findByCourseIdAndStudentId(courseId, studentId);
    }

    public List<Review> getCourseReviews(String courseId) {
        return reviewRepository.findByCourseIdAndReviewStatus(courseId, "published");
    }

    public List<Review> getStudentReviews(String studentId) {
        return reviewRepository.findByStudentId(studentId);
    }

    public long getCourseReviewCount(String courseId) {
        return reviewRepository.countByCourseId(courseId);
    }

    public BigDecimal getCourseAverageRating(String courseId) {
        BigDecimal avg = reviewRepository.findAverageRatingByCourseId(courseId);
        return avg != null ? avg : BigDecimal.ZERO;
    }

    @Transactional
    public Review updateReview(String reviewId, Integer rating, String content) {
        Review review = getReviewById(reviewId);
        if (rating != null) {
            if (rating < 1 || rating > 5) {
                throw new BusinessException(400, "评分必须在1-5之间");
            }
            review.setReviewRating(rating);
        }
        if (content != null) {
            review.setReviewContent(content);
        }
        Review saved = reviewRepository.save(review);
        logger.info("更新评价: review={}", reviewId);
        analysisService.updateAverageRating(review.getCourseId());
        return saved;
    }

    @Transactional
    public void deleteReview(String reviewId) {
        Review review = getReviewById(reviewId);
        String courseId = review.getCourseId();
        reviewRepository.delete(review);
        logger.info("删除评价: review={}", reviewId);
        analysisService.updateAverageRating(courseId);
    }

    public long getTotalReviewCount() {
        return reviewRepository.count();
    }
}
