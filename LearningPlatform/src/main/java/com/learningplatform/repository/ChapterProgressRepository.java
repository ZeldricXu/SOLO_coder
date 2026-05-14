
package com.learningplatform.repository;

import com.learningplatform.entity.ChapterProgress;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ChapterProgressRepository extends JpaRepository<ChapterProgress, String> {

    List<ChapterProgress> findByProgressId(String progressId);

    List<ChapterProgress> findByCourseIdAndStudentId(String courseId, String studentId);

    Optional<ChapterProgress> findByProgressIdAndChapterId(String progressId, String chapterId);

    Optional<ChapterProgress> findByCourseIdAndStudentIdAndChapterId(String courseId, String studentId, String chapterId);

    long countByProgressIdAndIsCompleted(String progressId, Boolean isCompleted);
}
