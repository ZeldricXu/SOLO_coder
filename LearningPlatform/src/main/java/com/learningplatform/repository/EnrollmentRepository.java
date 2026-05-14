
package com.learningplatform.repository;

import com.learningplatform.entity.Enrollment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface EnrollmentRepository extends JpaRepository<Enrollment, String> {

    Optional<Enrollment> findByCourseIdAndStudentId(String courseId, String studentId);

    List<Enrollment> findByStudentId(String studentId);

    List<Enrollment> findByCourseId(String courseId);

    boolean existsByCourseIdAndStudentId(String courseId, String studentId);

    long countByCourseId(String courseId);

    long countByStudentId(String studentId);
}
