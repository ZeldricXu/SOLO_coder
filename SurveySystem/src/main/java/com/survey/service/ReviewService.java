package com.survey.service;

import com.survey.common.SurveyConstants;
import com.survey.dto.ReviewRequest;
import com.survey.entity.AnswerRecord;
import com.survey.entity.ReviewRecord;
import com.survey.exception.SurveyException;
import com.survey.repository.ReviewRecordRepository;
import com.survey.util.IdGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class ReviewService {

    private final ReviewRecordRepository reviewRecordRepository;
    private final AnswerService answerService;
    private final StatisticsService statisticsService;
    private final HistoryService historyService;

    @Transactional
    public ReviewRecord createReviewRequest(String answerId) {
        log.info("创建审核请求: {}", answerId);

        Optional<ReviewRecord> existing = reviewRecordRepository.findByAnswerId(answerId);
        if (existing.isPresent()) {
            return existing.get();
        }

        ReviewRecord record = new ReviewRecord();
        record.setReviewId(IdGenerator.generateReviewId());
        record.setAnswerId(answerId);
        record.setReviewStatus(SurveyConstants.REVIEW_STATUS_PENDING);
        record.setReviewTime(LocalDateTime.now());

        ReviewRecord saved = reviewRecordRepository.save(record);
        answerService.setReviewId(answerId, saved.getReviewId());

        historyService.recordReviewHistory(saved.getReviewId(), "CREATE_REVIEW",
                "创建审核请求，答卷ID: " + answerId, null);

        log.info("审核请求创建成功: {}", saved.getReviewId());
        return saved;
    }

    @Transactional
    public ReviewRecord processReview(ReviewRequest request) {
        log.info("处理审核，答卷ID: {}", request.getAnswerId());

        ReviewRecord record = reviewRecordRepository.findByAnswerId(request.getAnswerId())
                .orElseThrow(() -> new SurveyException(404, "审核记录不存在，答卷ID: " + request.getAnswerId()));

        if (!SurveyConstants.REVIEW_STATUS_PENDING.equals(record.getReviewStatus())) {
            throw new SurveyException(400, "审核已处理，状态: " + record.getReviewStatus());
        }

        String newStatus = request.getReviewStatus();
        if (!SurveyConstants.REVIEW_STATUS_APPROVED.equals(newStatus) &&
                !SurveyConstants.REVIEW_STATUS_REJECTED.equals(newStatus)) {
            throw new SurveyException(400, "无效的审核状态: " + newStatus);
        }

        record.setReviewStatus(newStatus);
        record.setReviewComment(request.getReviewComment());
        record.setReviewerId(request.getReviewerId());
        record.setReviewTime(LocalDateTime.now());

        ReviewRecord saved = reviewRecordRepository.save(record);

        AnswerRecord answerRecord = answerService.getAnswer(request.getAnswerId());
        if (SurveyConstants.REVIEW_STATUS_APPROVED.equals(newStatus)) {
            answerService.updateAnswerStatus(request.getAnswerId(), SurveyConstants.ANSWER_STATUS_REVIEWED);
            statisticsService.updateStatistics(answerRecord.getSurveyId());
        } else {
            answerService.updateAnswerStatus(request.getAnswerId(), SurveyConstants.ANSWER_STATUS_REJECTED);
        }

        historyService.recordReviewHistory(saved.getReviewId(), "PROCESS_REVIEW",
                "审核处理结果: " + newStatus + ", 答卷ID: " + request.getAnswerId(), request.getReviewerId());
        historyService.recordAnswerHistory(request.getAnswerId(), "REVIEW_PROCESSED",
                "审核完成，结果: " + newStatus, request.getReviewerId());

        log.info("审核处理完成: {}", saved.getReviewId());
        return saved;
    }

    public ReviewRecord getReview(String reviewId) {
        return reviewRecordRepository.findByReviewId(reviewId)
                .orElseThrow(() -> new SurveyException(404, "审核记录不存在: " + reviewId));
    }

    public Optional<ReviewRecord> getReviewByAnswer(String answerId) {
        return reviewRecordRepository.findByAnswerId(answerId);
    }

    public List<ReviewRecord> getPendingReviews() {
        return reviewRecordRepository.findByReviewStatus(SurveyConstants.REVIEW_STATUS_PENDING);
    }

    public List<ReviewRecord> getReviewsByStatus(String status) {
        return reviewRecordRepository.findByReviewStatus(status);
    }
}
