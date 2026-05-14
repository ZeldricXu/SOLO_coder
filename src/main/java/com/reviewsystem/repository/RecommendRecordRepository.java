package com.reviewsystem.repository;

import com.reviewsystem.model.RecommendRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface RecommendRecordRepository extends JpaRepository<RecommendRecord, String> {

    Optional<RecommendRecord> findByCommentId(String commentId);

    List<RecommendRecord> findByContentId(String contentId);

    List<RecommendRecord> findByContentIdOrderByRecommendScoreDesc(String contentId);

    @Query("SELECT r FROM RecommendRecord r WHERE r.contentId = :contentId ORDER BY r.recommendScore DESC")
    List<RecommendRecord> findTopRecommendations(@Param("contentId") String contentId,
                                                  org.springframework.data.domain.Pageable pageable);

    @Query("SELECT r FROM RecommendRecord r WHERE r.contentId = :contentId AND r.recommendType = :type ORDER BY r.recommendScore DESC")
    List<RecommendRecord> findByContentIdAndTypeOrderByScoreDesc(@Param("contentId") String contentId,
                                                                 @Param("type") String type);

    void deleteByCommentId(String commentId);
}
