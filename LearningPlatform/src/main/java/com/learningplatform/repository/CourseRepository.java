
package com.learningplatform.repository;

import com.learningplatform.entity.Course;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface CourseRepository extends JpaRepository<Course, String> {

    List<Course> findByCourseStatus(String status);

    List<Course> findByCourseTeacher(String teacher);

    List<Course> findByCourseType(String courseType);
}
