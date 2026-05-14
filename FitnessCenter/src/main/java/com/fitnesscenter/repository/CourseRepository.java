package com.fitnesscenter.repository;

import com.fitnesscenter.model.Course;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public interface CourseRepository extends JpaRepository<Course, String> {
    
    Optional<Course> findByCourseId(String courseId);
    
    List<Course> findByCourseStatus(String courseStatus);
    
    List<Course> findByCourseType(String courseType);
    
    List<Course> findByCourseCoach(String courseCoach);
    
    List<Course> findByGymId(String gymId);
    
    List<Course> findByCourseTimeBetween(Instant startTime, Instant endTime);
    
    List<Course> findByCourseStatusAndCourseAvailableGreaterThan(String courseStatus, Integer available);
    
    boolean existsByCourseId(String courseId);
}
