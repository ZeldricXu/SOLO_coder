package com.reviewsystem.repository;

import com.reviewsystem.model.CommentStat;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface CommentStatRepository extends JpaRepository<CommentStat, String> {

    Optional<CommentStat> findByContentIdAndStatDate(String contentId, LocalDate statDate);

    List<CommentStat> findByContentId(String contentId);

    List<CommentStat> findByContentIdOrderByStatDateDesc(String contentId);

    List<CommentStat> findByStatDate(LocalDate statDate);

    @Query("SELECT c FROM CommentStat c WHERE c.contentId = :contentId AND c.statDate BETWEEN :startDate AND :endDate ORDER BY c.statDate")
    List<CommentStat> findByContentIdAndDateRange(@Param("contentId") String contentId,
                                                   @Param("startDate") LocalDate startDate,
                                                   @Param("endDate") LocalDate endDate);

    @Query("SELECT SUM(c.totalComments) FROM CommentStat c WHERE c.contentId = :contentId")
    Long sumTotalCommentsByContentId(@Param("contentId") String contentId);

    @Query("SELECT SUM(c.publishedComments) FROM CommentStat c WHERE c.contentId = :contentId")
    Long sumPublishedCommentsByContentId(@Param("contentId") String contentId);

    @Query("SELECT AVG(c.avgQualityScore) FROM CommentStat c WHERE c.contentId = :contentId")
    Double findAvgQualityByContentId(@Param("contentId") String contentId);
}
