package com.cms.repository;

import com.cms.entity.ContentStatistics;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface ContentStatisticsRepository extends JpaRepository<ContentStatistics, String> {

    Optional<ContentStatistics> findByContentId(String contentId);

    @Query("SELECT SUM(c.viewCount) FROM ContentStatistics c")
    Long sumAllViewCount();

    @Query("SELECT SUM(c.likeCount) FROM ContentStatistics c")
    Long sumAllLikeCount();

    @Query("SELECT SUM(c.commentCount) FROM ContentStatistics c")
    Long sumAllCommentCount();

    @Query("SELECT SUM(c.shareCount) FROM ContentStatistics c")
    Long sumAllShareCount();
}
