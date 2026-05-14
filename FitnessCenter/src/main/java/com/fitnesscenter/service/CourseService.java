package com.fitnesscenter.service;

import com.fitnesscenter.config.CourseTypeConfig;
import com.fitnesscenter.model.Course;
import com.fitnesscenter.repository.CourseRepository;
import com.fitnesscenter.util.IdGenerator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Service
public class CourseService {

    private final CourseRepository courseRepository;
    private final CourseTypeConfig courseTypeConfig;

    public CourseService(CourseRepository courseRepository, CourseTypeConfig courseTypeConfig) {
        this.courseRepository = courseRepository;
        this.courseTypeConfig = courseTypeConfig;
    }

    @Transactional
    public Course createCourse(Course course) {
        validateCourseType(course.getCourseType());

        course.setCourseId(IdGenerator.generateCourseId());
        if (course.getCourseStatus() == null) {
            course.setCourseStatus("scheduled");
        }
        if (course.getCourseAvailable() == null) {
            course.setCourseAvailable(course.getCourseCapacity());
        }
        return courseRepository.save(course);
    }

    private void validateCourseType(String courseType) {
        if (courseType != null && !courseType.isEmpty()) {
            if (!courseTypeConfig.isTypeEnabled(courseType)) {
                throw new IllegalArgumentException("课程类型 '" + courseType + "' 未启用或不存在");
            }
        }
    }

    @Transactional(readOnly = true)
    public Course getCourseById(String courseId) {
        return courseRepository.findByCourseId(courseId)
                .orElseThrow(() -> new IllegalArgumentException("课程不存在"));
    }

    @Transactional(readOnly = true)
    public List<Course> getAllCourses() {
        return courseRepository.findAll();
    }

    @Transactional(readOnly = true)
    public List<Course> getAvailableCourses() {
        return courseRepository.findByCourseStatusAndCourseAvailableGreaterThan("scheduled", 0);
    }

    @Transactional(readOnly = true)
    public List<Course> getCoursesByType(String courseType) {
        validateCourseType(courseType);
        return courseRepository.findByCourseType(courseType);
    }

    @Transactional(readOnly = true)
    public List<Course> getCoursesByCoach(String coachId) {
        return courseRepository.findByCourseCoach(coachId);
    }

    @Transactional
    public Course updateCourseStatus(String courseId, String status) {
        Course course = courseRepository.findByCourseId(courseId)
                .orElseThrow(() -> new IllegalArgumentException("课程不存在"));

        course.setCourseStatus(status);
        return courseRepository.save(course);
    }

    @Transactional
    public boolean decreaseAvailableSlots(String courseId) {
        Course course = courseRepository.findByCourseId(courseId)
                .orElseThrow(() -> new IllegalArgumentException("课程不存在"));

        if (course.getCourseAvailable() <= 0) {
            return false;
        }

        course.setCourseAvailable(course.getCourseAvailable() - 1);
        courseRepository.save(course);
        return true;
    }

    @Transactional
    public boolean increaseAvailableSlots(String courseId) {
        Course course = courseRepository.findByCourseId(courseId)
                .orElseThrow(() -> new IllegalArgumentException("课程不存在"));

        if (course.getCourseAvailable() >= course.getCourseCapacity()) {
            return false;
        }

        course.setCourseAvailable(course.getCourseAvailable() + 1);
        courseRepository.save(course);
        return true;
    }

    @Transactional
    public void validateCourseStatus(String courseId) {
        Course course = courseRepository.findByCourseId(courseId)
                .orElseThrow(() -> new IllegalArgumentException("课程不存在"));

        if ("cancelled".equals(course.getCourseStatus())) {
            throw new IllegalStateException("课程已取消");
        }
        if (course.getCourseAvailable() <= 0) {
            throw new IllegalStateException("课程名额已满");
        }
    }

    public Set<String> getAllEnabledCourseTypes() {
        return courseTypeConfig.getAllEnabledTypes();
    }

    public Map<String, String> getAllCourseTypesWithDescription() {
        return courseTypeConfig.getAllTypesWithDescription();
    }

    public String getCourseTypeDescription(String courseType) {
        return courseTypeConfig.getTypeDescription(courseType);
    }

    public boolean isCourseTypeEnabled(String courseType) {
        return courseTypeConfig.isTypeEnabled(courseType);
    }

    public void enableCourseType(String courseType) {
        courseTypeConfig.enableCourseType(courseType);
    }

    public void disableCourseType(String courseType) {
        courseTypeConfig.disableCourseType(courseType);
    }

    public void addCourseType(String typeCode, String description, boolean enabled) {
        courseTypeConfig.addCourseType(typeCode, description, enabled);
    }

    public CourseTypeConfig getCourseTypeConfig() {
        return courseTypeConfig;
    }
}
