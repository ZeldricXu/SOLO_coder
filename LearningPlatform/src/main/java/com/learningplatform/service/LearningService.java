
package com.learningplatform.service;

import com.learningplatform.dto.StartLearningResponse;
import com.learningplatform.dto.UpdateProgressResponse;
import com.learningplatform.entity.Chapter;
import com.learningplatform.entity.ChapterProgress;
import com.learningplatform.entity.Progress;
import com.learningplatform.exception.BusinessException;
import com.learningplatform.repository.ChapterProgressRepository;
import com.learningplatform.repository.ProgressRepository;
import com.learningplatform.util.IdGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Service
public class LearningService {

    private static final Logger logger = LoggerFactory.getLogger(LearningService.class);

    @Autowired
    private ProgressRepository progressRepository;

    @Autowired
    private ChapterProgressRepository chapterProgressRepository;

    @Autowired
    private CourseService courseService;

    @Autowired
    private ChapterService chapterService;

    @Autowired
    private StudentService studentService;

    @Autowired
    private HistoryService historyService;

    @Autowired
    private AnalysisService analysisService;

    @Transactional
    public StartLearningResponse startLearning(String courseId, String studentId) {
        courseService.validateCourseAvailability(courseId);
        
        studentService.getStudentById(studentId);

        if (!studentService.isEnrolled(studentId, courseId)) {
            studentService.enrollCourse(studentId, courseId);
            studentService.getEnrollment(studentId, courseId)
                    .ifPresent(e -> historyService.recordEnrollment(studentId, courseId, e.getEnrollmentId()));
        }

        Optional<Progress> existingProgress = progressRepository.findByCourseIdAndStudentId(courseId, studentId);
        if (existingProgress.isPresent()) {
            Progress progress = existingProgress.get();
            if ("completed".equals(progress.getProgressStatus())) {
                throw new BusinessException(400, "课程已完成学习");
            }
            logger.info("学员已在学习中: student={}, course={}", studentId, courseId);
            return new StartLearningResponse(progress.getProgressId(), progress.getProgressStatus());
        }

        List<Chapter> chapters = chapterService.getPublishedChaptersByCourse(courseId);
        int totalChapters = chapters.size();

        Progress progress = new Progress();
        progress.setProgressId(IdGenerator.generateProgressId());
        progress.setCourseId(courseId);
        progress.setStudentId(studentId);
        progress.setProgressStatus("in_progress");
        progress.setProgressPercent(0);
        progress.setChaptersCompleted(0);
        progress.setTotalChapters(totalChapters);
        progress.setLearningTime(0L);
        Progress savedProgress = progressRepository.save(progress);

        for (Chapter chapter : chapters) {
            ChapterProgress chapterProgress = new ChapterProgress();
            chapterProgress.setChapterProgressId(IdGenerator.generateChapterProgressId());
            chapterProgress.setProgressId(savedProgress.getProgressId());
            chapterProgress.setCourseId(courseId);
            chapterProgress.setStudentId(studentId);
            chapterProgress.setChapterId(chapter.getChapterId());
            chapterProgress.setIsCompleted(false);
            chapterProgress.setLearningTime(0L);
            chapterProgressRepository.save(chapterProgress);
        }

        historyService.recordCourseStart(studentId, courseId, savedProgress.getProgressId());
        analysisService.incrementEnrollmentCount();

        logger.info("开始学习课程: student={}, course={}, progress={}", studentId, courseId, savedProgress.getProgressId());
        return new StartLearningResponse(savedProgress.getProgressId(), savedProgress.getProgressStatus());
    }

    @Transactional
    public UpdateProgressResponse updateProgress(String progressId, String chapterId, Boolean completed, Long learningTime) {
        Progress progress = progressRepository.findById(progressId)
                .orElseThrow(() -> new BusinessException(404, "学习进度不存在: " + progressId));

        if ("completed".equals(progress.getProgressStatus())) {
            throw new BusinessException(400, "课程已完成，无需更新进度");
        }

        chapterService.validateChapterBelongsToCourse(chapterId, progress.getCourseId());

        ChapterProgress chapterProgress = chapterProgressRepository
                .findByProgressIdAndChapterId(progressId, chapterId)
                .orElseThrow(() -> new BusinessException(404, "章节进度不存在"));

        if (learningTime != null && learningTime > 0) {
            progress.setLearningTime(progress.getLearningTime() + learningTime);
            chapterProgress.setLearningTime(chapterProgress.getLearningTime() + learningTime);
        }

        if (Boolean.TRUE.equals(completed) && !chapterProgress.getIsCompleted()) {
            chapterProgress.setIsCompleted(true);
            chapterProgress.setCompletedAt(LocalDateTime.now());
            historyService.recordChapterComplete(progress.getStudentId(), progress.getCourseId(), chapterId, progressId);
        }

        chapterProgressRepository.save(chapterProgress);

        long completedChapters = chapterProgressRepository.countByProgressIdAndIsCompleted(progressId, true);
        progress.setChaptersCompleted((int) completedChapters);

        int progressPercent = 0;
        if (progress.getTotalChapters() > 0) {
            progressPercent = (int) (completedChapters * 100 / progress.getTotalChapters());
        }
        progress.setProgressPercent(progressPercent);

        boolean allCompleted = completedChapters >= progress.getTotalChapters();
        if (allCompleted) {
            progress.setProgressStatus("completed");
            progress.setCompletedAt(LocalDateTime.now());
            historyService.recordCourseComplete(progress.getStudentId(), progress.getCourseId(), progressId);
            studentService.incrementCompletedCourses(progress.getStudentId());
            analysisService.incrementCompletionCount();
            logger.info("课程学习完成: progress={}, student={}, course={}", progressId, progress.getStudentId(), progress.getCourseId());
        }

        Progress savedProgress = progressRepository.save(progress);
        historyService.recordProgressUpdate(progress.getStudentId(), progress.getCourseId(), chapterId, progressId, progressPercent);

        UpdateProgressResponse response = new UpdateProgressResponse();
        response.setProgressPercent(savedProgress.getProgressPercent());
        response.setProgressStatus(savedProgress.getProgressStatus());
        response.setChaptersCompleted(savedProgress.getChaptersCompleted());
        response.setTotalChapters(savedProgress.getTotalChapters());

        logger.info("更新学习进度: progress={}, percent={}%, status={}", progressId, progressPercent, savedProgress.getProgressStatus());
        return response;
    }

    public Progress getProgress(String progressId) {
        return progressRepository.findById(progressId)
                .orElseThrow(() -> new BusinessException(404, "学习进度不存在: " + progressId));
    }

    public Optional<Progress> getProgressByCourseAndStudent(String courseId, String studentId) {
        return progressRepository.findByCourseIdAndStudentId(courseId, studentId);
    }

    public List<Progress> getStudentProgresses(String studentId) {
        return progressRepository.findByStudentId(studentId);
    }

    public List<Progress> getCourseProgresses(String courseId) {
        return progressRepository.findByCourseId(courseId);
    }

    public List<ChapterProgress> getChapterProgresses(String progressId) {
        return chapterProgressRepository.findByProgressId(progressId);
    }

    public long getTotalProgressCount() {
        return progressRepository.count();
    }

    public long getCompletedProgressCount() {
        return progressRepository.count();
    }
}
