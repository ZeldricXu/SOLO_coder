
package com.learningplatform.repository;

import com.learningplatform.entity.Review;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

@Repository
public interface ReviewRepository extends JpaRepository<Review, String> {

    List<Review> findByCourseId(String courseId);

    List<Review> findByStudentId(String studentId);

    Optional<Review> findByCourseIdAndStudentId(String courseId, String studentId);

    List<Review> findByCourseIdAndReviewStatus(String courseId, String status);

    long countByCourseId(String courseId);

    @Query("SELECT AVG(r.reviewRating) FROM Review r WHERE r.courseId = :courseId AND r.reviewStatus = 'published'")
    BigDecimal findAverageRatingByCourseId(String courseId);
}
