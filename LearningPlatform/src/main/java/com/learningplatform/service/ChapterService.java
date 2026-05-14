
package com.learningplatform.service;

import com.learningplatform.entity.Chapter;
import com.learningplatform.exception.BusinessException;
import com.learningplatform.repository.ChapterRepository;
import com.learningplatform.util.IdGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Service
public class ChapterService {

    private static final Logger logger = LoggerFactory.getLogger(ChapterService.class);

    @Autowired
    private ChapterRepository chapterRepository;

    @Autowired
    private CourseService courseService;

    @Transactional
    public Chapter createChapter(Chapter chapter) {
        courseService.getCourseById(chapter.getCourseId());
        
        if (chapter.getChapterId() == null || chapter.getChapterId().isEmpty()) {
            chapter.setChapterId(IdGenerator.generateChapterId());
        }
        if (chapter.getChapterStatus() == null) {
            chapter.setChapterStatus("draft");
        }
        if (chapter.getChapterOrder() == null) {
            long count = chapterRepository.countByCourseId(chapter.getCourseId());
            chapter.setChapterOrder((int) count + 1);
        }
        Chapter saved = chapterRepository.save(chapter);
        logger.info("创建章节成功: {}", saved.getChapterId());
        return saved;
    }

    @Transactional
    public Chapter updateChapter(String chapterId, Chapter chapter) {
        Chapter existing = getChapterById(chapterId);
        if (chapter.getChapterName() != null) {
            existing.setChapterName(chapter.getChapterName());
        }
        if (chapter.getChapterOrder() != null) {
            existing.setChapterOrder(chapter.getChapterOrder());
        }
        if (chapter.getChapterDuration() != null) {
            existing.setChapterDuration(chapter.getChapterDuration());
        }
        if (chapter.getChapterStatus() != null) {
            existing.setChapterStatus(chapter.getChapterStatus());
        }
        if (chapter.getChapterDescription() != null) {
            existing.setChapterDescription(chapter.getChapterDescription());
        }
        Chapter saved = chapterRepository.save(existing);
        logger.info("更新章节成功: {}", chapterId);
        return saved;
    }

    public Chapter getChapterById(String chapterId) {
        return chapterRepository.findById(chapterId)
                .orElseThrow(() -> new BusinessException(404, "章节不存在: " + chapterId));
    }

    public Optional<Chapter> findChapterById(String chapterId) {
        return chapterRepository.findById(chapterId);
    }

    public List<Chapter> getChaptersByCourse(String courseId) {
        return chapterRepository.findByCourseIdOrderByChapterOrderAsc(courseId);
    }

    public List<Chapter> getPublishedChaptersByCourse(String courseId) {
        return chapterRepository.findByCourseIdAndChapterStatus(courseId, "published");
    }

    public int getTotalChaptersCount(String courseId) {
        return (int) chapterRepository.countByCourseId(courseId);
    }

    @Transactional
    public void deleteChapter(String chapterId) {
        Chapter chapter = getChapterById(chapterId);
        chapterRepository.delete(chapter);
        logger.info("删除章节成功: {}", chapterId);
    }

    @Transactional
    public Chapter publishChapter(String chapterId) {
        Chapter chapter = getChapterById(chapterId);
        chapter.setChapterStatus("published");
        Chapter saved = chapterRepository.save(chapter);
        logger.info("发布章节成功: {}", chapterId);
        return saved;
    }

    public void validateChapterBelongsToCourse(String chapterId, String courseId) {
        Chapter chapter = getChapterById(chapterId);
        if (!chapter.getCourseId().equals(courseId)) {
            throw new BusinessException(400, "章节不属于该课程");
        }
    }
}
