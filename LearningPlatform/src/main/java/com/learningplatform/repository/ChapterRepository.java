
package com.learningplatform.repository;

import com.learningplatform.entity.Chapter;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ChapterRepository extends JpaRepository<Chapter, String> {

    List<Chapter> findByCourseIdOrderByChapterOrderAsc(String courseId);

    List<Chapter> findByCourseIdAndChapterStatus(String courseId, String status);

    Optional<Chapter> findByChapterIdAndCourseId(String chapterId, String courseId);

    long countByCourseId(String courseId);

    long countByCourseIdAndChapterStatus(String courseId, String status);
}
