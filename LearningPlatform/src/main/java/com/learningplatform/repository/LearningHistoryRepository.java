
package com.learningplatform.repository;

import com.learningplatform.entity.LearningHistory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface LearningHistoryRepository extends JpaRepository<LearningHistory, String> {

    List<LearningHistory> findByStudentIdOrderByCreatedAtDesc(String studentId);

    List<LearningHistory> findByStudentIdAndCourseIdOrderByCreatedAtDesc(String studentId, String courseId);

    List<LearningHistory> findByHistoryTypeOrderByCreatedAtDesc(String historyType);
}
