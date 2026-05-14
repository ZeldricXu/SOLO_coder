
package com.learningplatform.repository;

import com.learningplatform.entity.Progress;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ProgressRepository extends JpaRepository<Progress, String> {

    Optional<Progress> findByCourseIdAndStudentId(String courseId, String studentId);

    List<Progress> findByStudentId(String studentId);

    List<Progress> findByCourseId(String courseId);

    List<Progress> findByStudentIdAndProgressStatus(String studentId, String status);

    long countByCourseId(String courseId);

    long countByCourseIdAndProgressStatus(String courseId, String status);
}
