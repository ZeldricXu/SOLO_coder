
package com.learningplatform.service;

import com.learningplatform.entity.LearningHistory;
import com.learningplatform.repository.LearningHistoryRepository;
import com.learningplatform.util.IdGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class HistoryService {

    private static final Logger logger = LoggerFactory.getLogger(HistoryService.class);

    @Autowired
    private LearningHistoryRepository learningHistoryRepository;

    @Transactional
    public LearningHistory recordHistory(String studentId, String courseId, String chapterId,
                                          String historyType, String historyAction, String historyDetail) {
        LearningHistory history = new LearningHistory();
        history.setHistoryId(IdGenerator.generateHistoryId());
        history.setStudentId(studentId);
        history.setCourseId(courseId);
        history.setChapterId(chapterId);
        history.setHistoryType(historyType);
        history.setHistoryAction(historyAction);
        history.setHistoryDetail(historyDetail);

        LearningHistory saved = learningHistoryRepository.save(history);
        logger.debug("记录学习历史: student={}, action={}", studentId, historyAction);
        return saved;
    }

    @Transactional
    public LearningHistory recordCourseStart(String studentId, String courseId, String progressId) {
        return recordHistory(studentId, courseId, null, "learning", "start_course",
                "开始学习课程，进度ID: " + progressId);
    }

    @Transactional
    public LearningHistory recordProgressUpdate(String studentId, String courseId, String chapterId,
                                                 String progressId, int progressPercent) {
        return recordHistory(studentId, courseId, chapterId, "learning", "update_progress",
                String.format("更新学习进度: 进度ID=%s, 完成度=%d%%", progressId, progressPercent));
    }

    @Transactional
    public LearningHistory recordChapterComplete(String studentId, String courseId, String chapterId,
                                                  String progressId) {
        return recordHistory(studentId, courseId, chapterId, "learning", "complete_chapter",
                String.format("完成章节学习: 进度ID=%s", progressId));
    }

    @Transactional
    public LearningHistory recordCourseComplete(String studentId, String courseId, String progressId) {
        return recordHistory(studentId, courseId, null, "learning", "complete_course",
                String.format("完成课程学习: 进度ID=%s", progressId));
    }

    @Transactional
    public LearningHistory recordCertificateGenerate(String studentId, String courseId, String certificateId,
                                                      String certificateNumber) {
        return recordHistory(studentId, courseId, null, "certificate", "generate_certificate",
                String.format("生成证书: 证书ID=%s, 证书编号=%s", certificateId, certificateNumber));
    }

    @Transactional
    public LearningHistory recordReviewSubmit(String studentId, String courseId, String reviewId, int rating) {
        return recordHistory(studentId, courseId, null, "review", "submit_review",
                String.format("提交课程评价: 评价ID=%s, 评分=%d", reviewId, rating));
    }

    @Transactional
    public LearningHistory recordEnrollment(String studentId, String courseId, String enrollmentId) {
        return recordHistory(studentId, courseId, null, "enrollment", "enroll_course",
                String.format("注册课程: 注册ID=%s", enrollmentId));
    }

    public List<LearningHistory> getStudentHistory(String studentId) {
        return learningHistoryRepository.findByStudentIdOrderByCreatedAtDesc(studentId);
    }

    public List<LearningHistory> getStudentCourseHistory(String studentId, String courseId) {
        return learningHistoryRepository.findByStudentIdAndCourseIdOrderByCreatedAtDesc(studentId, courseId);
    }

    public List<LearningHistory> getHistoryByType(String historyType) {
        return learningHistoryRepository.findByHistoryTypeOrderByCreatedAtDesc(historyType);
    }
}
