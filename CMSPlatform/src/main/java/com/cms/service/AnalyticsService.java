package com.cms.service;

import com.cms.entity.Content;
import com.cms.entity.ContentStatistics;
import com.cms.entity.MonthlyStatistics;
import com.cms.repository.ContentRepository;
import com.cms.repository.ContentStatisticsRepository;
import com.cms.repository.MonthlyStatisticsRepository;
import com.cms.repository.ReviewRecordRepository;
import com.cms.repository.PublishRecordRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
public class AnalyticsService {

    @Autowired
    private ContentRepository contentRepository;

    @Autowired
    private ContentStatisticsRepository contentStatisticsRepository;

    @Autowired
    private MonthlyStatisticsRepository monthlyStatisticsRepository;

    @Autowired
    private ReviewRecordRepository reviewRecordRepository;

    @Autowired
    private PublishRecordRepository publishRecordRepository;

    public ContentStatistics getContentStatistics(String contentId) {
        return contentStatisticsRepository.findByContentId(contentId)
                .orElse(null);
    }

    public Map<String, Object> getOverallStatistics() {
        Map<String, Object> stats = new HashMap<>();

        long totalContent = contentRepository.countAll();
        long publishedContent = contentRepository.countByStatus("published");
        long pendingReview = contentRepository.countByStatus("pending_review");
        long approvedContent = contentRepository.countByStatus("approved");
        long rejectedContent = contentRepository.countByStatus("rejected");

        stats.put("totalContent", totalContent);
        stats.put("publishedContent", publishedContent);
        stats.put("pendingReview", pendingReview);
        stats.put("approvedContent", approvedContent);
        stats.put("rejectedContent", rejectedContent);

        Long totalView = contentStatisticsRepository.sumAllViewCount();
        Long totalLike = contentStatisticsRepository.sumAllLikeCount();
        Long totalComment = contentStatisticsRepository.sumAllCommentCount();
        Long totalShare = contentStatisticsRepository.sumAllShareCount();

        stats.put("totalView", totalView != null ? totalView : 0);
        stats.put("totalLike", totalLike != null ? totalLike : 0);
        stats.put("totalComment", totalComment != null ? totalComment : 0);
        stats.put("totalShare", totalShare != null ? totalShare : 0);

        long approvedReviews = reviewRecordRepository.countByStatus("approved");
        long rejectedReviews = reviewRecordRepository.countByStatus("rejected");
        long publishedRecords = publishRecordRepository.countByStatus("published");

        stats.put("approvedReviews", approvedReviews);
        stats.put("rejectedReviews", rejectedReviews);
        stats.put("publishedRecords", publishedRecords);

        return stats;
    }

    @Transactional
    public MonthlyStatistics updateMonthlyStatistics() {
        String currentMonth = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM"));

        MonthlyStatistics monthlyStats = monthlyStatisticsRepository.findByStatMonth(currentMonth)
                .orElseGet(() -> {
                    MonthlyStatistics newStats = new MonthlyStatistics();
                    newStats.setStatId("mstat_" + UUID.randomUUID().toString().replace("-", "").substring(0, 10));
                    newStats.setStatMonth(currentMonth);
                    return newStats;
                });

        monthlyStats.setContentCount(contentRepository.countAll());
        monthlyStats.setPublishCount(publishRecordRepository.countByStatus("published"));
        monthlyStats.setReviewCount(reviewRecordRepository.countByStatus("approved") + reviewRecordRepository.countByStatus("rejected"));
        monthlyStats.setRejectCount(reviewRecordRepository.countByStatus("rejected"));

        Long totalView = contentStatisticsRepository.sumAllViewCount();
        Long totalComment = contentStatisticsRepository.sumAllCommentCount();
        Long totalLike = contentStatisticsRepository.sumAllLikeCount();
        Long totalShare = contentStatisticsRepository.sumAllShareCount();

        monthlyStats.setTotalView(totalView != null ? totalView : 0);
        monthlyStats.setTotalComment(totalComment != null ? totalComment : 0);
        monthlyStats.setTotalLike(totalLike != null ? totalLike : 0);
        monthlyStats.setTotalShare(totalShare != null ? totalShare : 0);

        return monthlyStatisticsRepository.save(monthlyStats);
    }

    public MonthlyStatistics getMonthlyStatistics(String month) {
        return monthlyStatisticsRepository.findByStatMonth(month).orElse(null);
    }

    public Map<String, Object> getContentAnalytics(String contentId) {
        Map<String, Object> analytics = new HashMap<>();

        Content content = contentRepository.findById(contentId).orElse(null);
        if (content == null) {
            return null;
        }

        analytics.put("contentId", content.getContentId());
        analytics.put("contentTitle", content.getContentTitle());
        analytics.put("contentStatus", content.getContentStatus());

        ContentStatistics stats = contentStatisticsRepository.findByContentId(contentId).orElse(null);
        if (stats != null) {
            analytics.put("viewCount", stats.getViewCount());
            analytics.put("likeCount", stats.getLikeCount());
            analytics.put("commentCount", stats.getCommentCount());
            analytics.put("shareCount", stats.getShareCount());
        } else {
            analytics.put("viewCount", 0);
            analytics.put("likeCount", 0);
            analytics.put("commentCount", 0);
            analytics.put("shareCount", 0);
        }

        return analytics;
    }
}
