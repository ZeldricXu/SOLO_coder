package com.cms.service;

import com.cms.dto.ReviewProcessRequest;
import com.cms.entity.Content;
import com.cms.entity.HistoryRecord;
import com.cms.entity.ReviewRecord;
import com.cms.exception.BusinessException;
import com.cms.repository.HistoryRecordRepository;
import com.cms.repository.ReviewRecordRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Service
public class ReviewService {

    private static final Logger logger = LoggerFactory.getLogger(ReviewService.class);

    @Autowired
    private ReviewRecordRepository reviewRecordRepository;

    @Autowired
    private ContentService contentService;

    @Autowired
    private HistoryRecordRepository historyRecordRepository;

    @Autowired
    private ReviewReminderService reviewReminderService;

    @Autowired
    private ContentTypeConfigService contentTypeConfigService;

    @Transactional
    public ReviewRecord processReview(ReviewProcessRequest request) {
        Content content = contentService.getContentById(request.getContentId());

        validateContentStatusForReview(content);

        String reviewStatus = request.getReviewStatus();
        if (!"approved".equals(reviewStatus) && !"rejected".equals(reviewStatus)) {
            throw new BusinessException(400, "无效的审核状态");
        }

        boolean isCompliant = checkContentCompliance(content);

        ReviewRecord reviewRecord = new ReviewRecord();
        reviewRecord.setReviewId("review_" + UUID.randomUUID().toString().replace("-", "").substring(0, 10));
        reviewRecord.setContentId(request.getContentId());
        reviewRecord.setReviewerId(request.getReviewerId());
        reviewRecord.setReviewerName(request.getReviewerName());
        reviewRecord.setReviewTime(LocalDateTime.now());

        if ("approved".equals(reviewStatus) && isCompliant) {
            reviewRecord.setReviewStatus("approved");
            reviewRecord.setReviewComment(request.getReviewComment() != null ? request.getReviewComment() : "审核通过");
        } else if ("rejected".equals(reviewStatus) || !isCompliant) {
            reviewRecord.setReviewStatus("rejected");
            reviewRecord.setReviewComment(request.getReviewComment() != null ? request.getReviewComment() : "审核拒绝");
        }

        ReviewRecord savedRecord = reviewRecordRepository.save(reviewRecord);

        String newStatus = reviewRecord.getReviewStatus();
        content.setContentStatus(newStatus);
        content.setReviewerId(request.getReviewerId());
        content.setReviewComment(reviewRecord.getReviewComment());
        content.setReviewedAt(LocalDateTime.now());
        contentService.updateStatus(request.getContentId(), newStatus, request.getReviewerId());

        recordReviewHistory(content, reviewRecord, request);

        reviewReminderService.cancelRemindersByContentId(request.getContentId());

        logger.info("完成内容审核: contentId={}, status={}, reviewerId={}", 
            request.getContentId(), newStatus, request.getReviewerId());

        return savedRecord;
    }

    private void validateContentStatusForReview(Content content) {
        String status = content.getContentStatus();
        if ("approved".equals(status)) {
            throw new BusinessException(400, "内容已审核");
        }
        if ("published".equals(status)) {
            throw new BusinessException(400, "内容已发布");
        }
        if (!"pending_review".equals(status)) {
            throw new BusinessException(400, "内容状态不允许审核");
        }
    }

    private boolean checkContentCompliance(Content content) {
        if (content.getContentTitle() == null || content.getContentTitle().trim().isEmpty()) {
            return false;
        }
        if (content.getContentBody() == null || content.getContentBody().trim().isEmpty()) {
            return false;
        }
        return true;
    }

    private void recordReviewHistory(Content content, ReviewRecord reviewRecord, ReviewProcessRequest request) {
        HistoryRecord history = new HistoryRecord();
        history.setHistoryId("history_" + UUID.randomUUID().toString().replace("-", "").substring(0, 10));
        history.setContentId(request.getContentId());
        history.setOperationType("review");
        history.setOperationName("approved".equals(reviewRecord.getReviewStatus()) ? "审核通过" : "审核拒绝");
        history.setOperationDetail(reviewRecord.getReviewComment());
        history.setOperatorId(request.getReviewerId());
        history.setOperatorName(request.getReviewerName());
        history.setOperationTime(LocalDateTime.now());
        history.setPreviousStatus("pending_review");
        history.setNewStatus(reviewRecord.getReviewStatus());
        historyRecordRepository.save(history);
    }

    public List<ReviewRecord> getReviewsByContentId(String contentId) {
        return reviewRecordRepository.findByContentId(contentId);
    }

    public List<ReviewRecord> getReviewsByReviewerId(String reviewerId) {
        return reviewRecordRepository.findByReviewerId(reviewerId);
    }

    public List<ReviewRecord> getReviewsByStatus(String status) {
        return reviewRecordRepository.findByReviewStatus(status);
    }

    public ReviewRecord getReviewById(String reviewId) {
        return reviewRecordRepository.findById(reviewId)
                .orElseThrow(() -> new BusinessException(404, "审核记录不存在"));
    }

    public List<ReviewRecord> getAllReviews() {
        return reviewRecordRepository.findAll();
    }

    public long getPendingReviewCount() {
        List<Content> pendingContents = contentService.getContentsByStatus("pending_review");
        return pendingContents != null ? pendingContents.size() : 0;
    }
}
