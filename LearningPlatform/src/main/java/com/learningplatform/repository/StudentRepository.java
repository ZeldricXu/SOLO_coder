
package com.learningplatform.repository;

import com.learningplatform.entity.Student;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface StudentRepository extends JpaRepository<Student, String> {

    Optional<Student> findByStudentEmail(String email);

    Optional<Student> findByStudentPhone(String phone);
}
