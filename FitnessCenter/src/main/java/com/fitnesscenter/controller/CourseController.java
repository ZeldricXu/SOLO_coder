package com.fitnesscenter.controller;

import com.fitnesscenter.dto.ApiResponse;
import com.fitnesscenter.model.Course;
import com.fitnesscenter.service.CourseService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/courses")
public class CourseController {

    private final CourseService courseService;

    public CourseController(CourseService courseService) {
        this.courseService = courseService;
    }

    @PostMapping("/create")
    public ApiResponse<Course> createCourse(@RequestBody Course course) {
        Course savedCourse = courseService.createCourse(course);
        return ApiResponse.success(savedCourse);
    }

    @GetMapping("/{courseId}")
    public ApiResponse<Course> getCourseById(@PathVariable String courseId) {
        Course course = courseService.getCourseById(courseId);
        return ApiResponse.success(course);
    }

    @GetMapping
    public ApiResponse<List<Course>> getAllCourses() {
        List<Course> courses = courseService.getAllCourses();
        return ApiResponse.success(courses);
    }

    @GetMapping("/available")
    public ApiResponse<List<Course>> getAvailableCourses() {
        List<Course> courses = courseService.getAvailableCourses();
        return ApiResponse.success(courses);
    }

    @GetMapping("/type/{courseType}")
    public ApiResponse<List<Course>> getCoursesByType(@PathVariable String courseType) {
        List<Course> courses = courseService.getCoursesByType(courseType);
        return ApiResponse.success(courses);
    }

    @GetMapping("/coach/{coachId}")
    public ApiResponse<List<Course>> getCoursesByCoach(@PathVariable String coachId) {
        List<Course> courses = courseService.getCoursesByCoach(coachId);
        return ApiResponse.success(courses);
    }

    @PutMapping("/{courseId}/status")
    public ApiResponse<Course> updateCourseStatus(@PathVariable String courseId, @RequestParam String status) {
        Course course = courseService.updateCourseStatus(courseId, status);
        return ApiResponse.success(course);
    }
}
