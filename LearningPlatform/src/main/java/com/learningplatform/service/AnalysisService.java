
package com.learningplatform.service;

import com.learningplatform.entity.Statistics;
import com.learningplatform.repository.CertificateRepository;
import com.learningplatform.repository.CourseRepository;
import com.learningplatform.repository.ReviewRepository;
import com.learningplatform.repository.StatisticsRepository;
import com.learningplatform.repository.StudentRepository;
import com.learningplatform.util.IdGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Optional;

@Service
public class AnalysisService {

    private static final Logger logger = LoggerFactory.getLogger(AnalysisService.class);

    @Autowired
    private StatisticsRepository statisticsRepository;

    @Autowired
    private CourseRepository courseRepository;

    @Autowired
    private StudentRepository studentRepository;

    @Autowired
    private CertificateRepository certificateRepository;

    @Autowired
    private ReviewRepository reviewRepository;

    private String getCurrentMonth() {
        return LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM"));
    }

    public Statistics getCurrentStatistics() {
        String month = getCurrentMonth();
        return statisticsRepository.findByStatMonth(month)
                .orElseGet(() -> createEmptyStatistics(month));
    }

    private Statistics createEmptyStatistics(String month) {
        Statistics stats = new Statistics();
        stats.setStatId(IdGenerator.generateStatId());
        stats.setStatMonth(month);
        stats.setCourseCount(0);
        stats.setStudentCount(0);
        stats.setEnrollmentCount(0);
        stats.setCompletionCount(0);
        stats.setCertificateCount(0);
        stats.setReviewCount(0);
        stats.setAverageRating(BigDecimal.ZERO);
        return stats;
    }

    @Transactional
    public Statistics refreshStatistics() {
        String month = getCurrentMonth();
        Statistics stats = statisticsRepository.findByStatMonth(month)
                .orElse(createEmptyStatistics(month));

        stats.setCourseCount((int) courseRepository.count());
        stats.setStudentCount((int) studentRepository.count());
        stats.setCertificateCount((int) certificateRepository.count());
        stats.setReviewCount((int) reviewRepository.count());

        Statistics saved = statisticsRepository.save(stats);
        logger.info("刷新统计数据: month={}", month);
        return saved;
    }

    @Transactional
    public void incrementCourseCount() {
        Statistics stats = getCurrentStatistics();
        stats.setCourseCount(stats.getCourseCount() + 1);
        statisticsRepository.save(stats);
    }

    @Transactional
    public void incrementStudentCount() {
        Statistics stats = getCurrentStatistics();
        stats.setStudentCount(stats.getStudentCount() + 1);
        statisticsRepository.save(stats);
    }

    @Transactional
    public void incrementEnrollmentCount() {
        Statistics stats = getCurrentStatistics();
        stats.setEnrollmentCount(stats.getEnrollmentCount() + 1);
        statisticsRepository.save(stats);
    }

    @Transactional
    public void incrementCompletionCount() {
        Statistics stats = getCurrentStatistics();
        stats.setCompletionCount(stats.getCompletionCount() + 1);
        statisticsRepository.save(stats);
    }

    @Transactional
    public void incrementCertificateCount() {
        Statistics stats = getCurrentStatistics();
        stats.setCertificateCount(stats.getCertificateCount() + 1);
        statisticsRepository.save(stats);
    }

    @Transactional
    public void incrementReviewCount() {
        Statistics stats = getCurrentStatistics();
        stats.setReviewCount(stats.getReviewCount() + 1);
        statisticsRepository.save(stats);
    }

    @Transactional
    public void updateAverageRating(String courseId) {
        BigDecimal avgRating = reviewRepository.findAverageRatingByCourseId(courseId);
        if (avgRating != null && avgRating.compareTo(BigDecimal.ZERO) > 0) {
            Statistics stats = getCurrentStatistics();
            stats.setAverageRating(avgRating.setScale(2, RoundingMode.HALF_UP));
            statisticsRepository.save(stats);
        }
    }

    public double calculateCompletionRate() {
        Statistics stats = getCurrentStatistics();
        if (stats.getEnrollmentCount() == 0) {
            return 0.0;
        }
        return (double) stats.getCompletionCount() / stats.getEnrollmentCount() * 100;
    }

    public double calculateCertificateRate() {
        Statistics stats = getCurrentStatistics();
        if (stats.getCompletionCount() == 0) {
            return 0.0;
        }
        return (double) stats.getCertificateCount() / stats.getCompletionCount() * 100;
    }
}
