
package com.learningplatform.repository;

import com.learningplatform.entity.Certificate;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface CertificateRepository extends JpaRepository<Certificate, String> {

    Optional<Certificate> findByCertificateNumber(String certificateNumber);

    Optional<Certificate> findByCourseIdAndStudentId(String courseId, String studentId);

    List<Certificate> findByStudentId(String studentId);

    List<Certificate> findByCourseId(String courseId);

    boolean existsByCourseIdAndStudentId(String courseId, String studentId);

    long countByCourseId(String courseId);

    long countByStudentId(String studentId);
}
