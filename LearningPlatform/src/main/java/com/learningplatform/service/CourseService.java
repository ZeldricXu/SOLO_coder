
package com.learningplatform.service;

import com.learningplatform.config.CourseTypeConfig;
import com.learningplatform.entity.Course;
import com.learningplatform.exception.BusinessException;
import com.learningplatform.repository.CourseRepository;
import com.learningplatform.util.IdGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
public class CourseService {

    private static final Logger logger = LoggerFactory.getLogger(CourseService.class);

    @Autowired
    private CourseRepository courseRepository;

    @Autowired
    private CourseTypeConfig courseTypeConfig;

    @Transactional
    public Course createCourse(Course course) {
        if (course.getCourseId() == null || course.getCourseId().isEmpty()) {
            course.setCourseId(IdGenerator.generateCourseId());
        }
        if (course.getCourseStatus() == null) {
            course.setCourseStatus("draft");
        }
        
        String courseType = course.getCourseType();
        if (courseType != null && !courseType.isEmpty()) {
            if (!courseTypeConfig.isValidType(courseType)) {
                logger.warn("创建课程时使用了未配置的课程类型: {}", courseType);
            }
        } else {
            Optional<CourseTypeConfig.CourseType> defaultType = courseTypeConfig.getType("default");
            if (defaultType.isPresent()) {
                course.setCourseType(defaultType.get().getCode());
            }
        }

        Course saved = courseRepository.save(course);
        logger.info("创建课程成功: {}, type={}", saved.getCourseId(), saved.getCourseType());
        return saved;
    }

    @Transactional
    public Course updateCourse(String courseId, Course course) {
        Course existing = getCourseById(courseId);
        if (course.getCourseName() != null) {
            existing.setCourseName(course.getCourseName());
        }
        if (course.getCourseType() != null) {
            if (!courseTypeConfig.isValidType(course.getCourseType())) {
                logger.warn("更新课程时使用了未配置的课程类型: {}", course.getCourseType());
            }
            existing.setCourseType(course.getCourseType());
        }
        if (course.getCourseTeacher() != null) {
            existing.setCourseTeacher(course.getCourseTeacher());
        }
        if (course.getCourseDuration() != null) {
            existing.setCourseDuration(course.getCourseDuration());
        }
        if (course.getCourseStatus() != null) {
            existing.setCourseStatus(course.getCourseStatus());
        }
        if (course.getCoursePrice() != null) {
            existing.setCoursePrice(course.getCoursePrice());
        }
        if (course.getCourseDescription() != null) {
            existing.setCourseDescription(course.getCourseDescription());
        }
        Course saved = courseRepository.save(existing);
        logger.info("更新课程成功: {}, type={}", courseId, saved.getCourseType());
        return saved;
    }

    public Course getCourseById(String courseId) {
        return courseRepository.findById(courseId)
                .orElseThrow(() -> new BusinessException(404, "课程不存在: " + courseId));
    }

    public Optional<Course> findCourseById(String courseId) {
        return courseRepository.findById(courseId);
    }

    public List<Course> getAllCourses() {
        return courseRepository.findAll();
    }

    public List<Course> getPublishedCourses() {
        return courseRepository.findByCourseStatus("published");
    }

    public List<Course> getCoursesByTeacher(String teacher) {
        return courseRepository.findByCourseTeacher(teacher);
    }

    public List<Course> getCoursesByType(String typeCode) {
        Optional<CourseTypeConfig.CourseType> type = courseTypeConfig.getType(typeCode);
        if (type.isEmpty()) {
            logger.warn("查询课程时使用了未配置的课程类型: {}", typeCode);
        }
        return courseRepository.findByCourseType(typeCode);
    }

    @Transactional
    public void deleteCourse(String courseId) {
        Course course = getCourseById(courseId);
        courseRepository.delete(course);
        logger.info("删除课程成功: {}", courseId);
    }

    @Transactional
    public Course publishCourse(String courseId) {
        Course course = getCourseById(courseId);
        
        String courseType = course.getCourseType();
        if (courseType != null) {
            Optional<CourseTypeConfig.CourseType> type = courseTypeConfig.getType(courseType);
            if (type.isPresent() && type.get().isRequiresCertificate()) {
                logger.info("发布需要证书的课程类型: {}, course={}", courseType, courseId);
            }
        }
        
        course.setCourseStatus("published");
        Course saved = courseRepository.save(course);
        logger.info("发布课程成功: {}", courseId);
        return saved;
    }

    @Transactional
    public Course closeCourse(String courseId) {
        Course course = getCourseById(courseId);
        course.setCourseStatus("closed");
        Course saved = courseRepository.save(course);
        logger.info("关闭课程成功: {}", courseId);
        return saved;
    }

    public void validateCourseAvailability(String courseId) {
        Course course = getCourseById(courseId);
        String status = course.getCourseStatus();
        if ("draft".equals(status)) {
            throw new BusinessException(400, "课程未发布: " + courseId);
        }
        if ("closed".equals(status)) {
            throw new BusinessException(400, "课程已关闭: " + courseId);
        }
    }

    public List<CourseTypeConfig.CourseType> getAllCourseTypes() {
        return courseTypeConfig.getAllTypes();
    }

    public List<CourseTypeConfig.CourseType> getEnabledCourseTypes() {
        return courseTypeConfig.getEnabledTypes();
    }

    public Optional<CourseTypeConfig.CourseType> getCourseType(String code) {
        return courseTypeConfig.getType(code);
    }

    public boolean isValidCourseType(String code) {
        return courseTypeConfig.isValidType(code);
    }

    public void addCourseType(CourseTypeConfig.CourseType courseType) {
        courseTypeConfig.addCourseType(courseType);
        logger.info("添加课程类型: {} -> {}", courseType.getCode(), courseType.getName());
    }

    public void updateCourseType(String code, CourseTypeConfig.CourseType courseType) {
        courseTypeConfig.updateCourseType(code, courseType);
        logger.info("更新课程类型: {} -> {}", code, courseType.getName());
    }

    public boolean removeCourseType(String code) {
        boolean result = courseTypeConfig.removeCourseType(code);
        if (result) {
            logger.info("删除课程类型: {}", code);
        }
        return result;
    }

    public void reloadCourseTypes() {
        courseTypeConfig.reload();
        logger.info("重新加载课程类型配置");
    }

    public Map<String, CourseTypeConfig.CourseType> getCourseTypesMap() {
        return courseTypeConfig.getTypes();
    }
}
