
package com.learningplatform.controller;

import com.learningplatform.dto.ApiResponse;
import com.learningplatform.entity.Chapter;
import com.learningplatform.entity.Course;
import com.learningplatform.entity.Resource;
import com.learningplatform.entity.Review;
import com.learningplatform.service.ChapterService;
import com.learningplatform.service.CourseService;
import com.learningplatform.service.ResourceService;
import com.learningplatform.service.ReviewService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/courses")
public class CourseController {

    @Autowired
    private CourseService courseService;

    @Autowired
    private ChapterService chapterService;

    @Autowired
    private ResourceService resourceService;

    @Autowired
    private ReviewService reviewService;

    @PostMapping
    public ApiResponse<Course> createCourse(@RequestBody Course course) {
        Course saved = courseService.createCourse(course);
        return ApiResponse.success(saved);
    }

    @GetMapping
    public ApiResponse<List<Course>> getAllCourses() {
        List<Course> courses = courseService.getAllCourses();
        return ApiResponse.success(courses);
    }

    @GetMapping("/published")
    public ApiResponse<List<Course>> getPublishedCourses() {
        List<Course> courses = courseService.getPublishedCourses();
        return ApiResponse.success(courses);
    }

    @GetMapping("/{courseId}")
    public ApiResponse<Course> getCourseById(@PathVariable String courseId) {
        Course course = courseService.getCourseById(courseId);
        return ApiResponse.success(course);
    }

    @PutMapping("/{courseId}")
    public ApiResponse<Course> updateCourse(@PathVariable String courseId, @RequestBody Course course) {
        Course updated = courseService.updateCourse(courseId, course);
        return ApiResponse.success(updated);
    }

    @DeleteMapping("/{courseId}")
    public ApiResponse<Void> deleteCourse(@PathVariable String courseId) {
        courseService.deleteCourse(courseId);
        return ApiResponse.success(null);
    }

    @PostMapping("/{courseId}/publish")
    public ApiResponse<Course> publishCourse(@PathVariable String courseId) {
        Course course = courseService.publishCourse(courseId);
        return ApiResponse.success(course);
    }

    @PostMapping("/{courseId}/close")
    public ApiResponse<Course> closeCourse(@PathVariable String courseId) {
        Course course = courseService.closeCourse(courseId);
        return ApiResponse.success(course);
    }

    @GetMapping("/{courseId}/chapters")
    public ApiResponse<List<Chapter>> getCourseChapters(@PathVariable String courseId) {
        List<Chapter> chapters = chapterService.getChaptersByCourse(courseId);
        return ApiResponse.success(chapters);
    }

    @PostMapping("/{courseId}/chapters")
    public ApiResponse<Chapter> createChapter(@PathVariable String courseId, @RequestBody Chapter chapter) {
        chapter.setCourseId(courseId);
        Chapter saved = chapterService.createChapter(chapter);
        return ApiResponse.success(saved);
    }

    @GetMapping("/{courseId}/resources")
    public ApiResponse<List<Resource>> getCourseResources(@PathVariable String courseId) {
        List<Resource> resources = resourceService.getResourcesByCourse(courseId);
        return ApiResponse.success(resources);
    }

    @PostMapping("/{courseId}/resources/upload")
    public ApiResponse<Resource> uploadResource(
            @PathVariable String courseId,
            @RequestParam(required = false) String chapterId,
            @RequestParam("file") MultipartFile file,
            @RequestParam(required = false) String uploadedBy) {
        Resource resource = resourceService.uploadResource(courseId, chapterId, file, uploadedBy);
        return ApiResponse.success(resource);
    }

    @GetMapping("/{courseId}/reviews")
    public ApiResponse<List<Review>> getCourseReviews(@PathVariable String courseId) {
        List<Review> reviews = reviewService.getCourseReviews(courseId);
        return ApiResponse.success(reviews);
    }

    @GetMapping("/{courseId}/reviews/stats")
    public ApiResponse<Map<String, Object>> getCourseReviewStats(@PathVariable String courseId) {
        Map<String, Object> stats = new HashMap<>();
        stats.put("reviewCount", reviewService.getCourseReviewCount(courseId));
        stats.put("averageRating", reviewService.getCourseAverageRating(courseId));
        return ApiResponse.success(stats);
    }

    @PostMapping("/{courseId}/reviews")
    public ApiResponse<Review> submitReview(
            @PathVariable String courseId,
            @RequestParam String studentId,
            @RequestParam Integer rating,
            @RequestParam(required = false) String content) {
        Review review = reviewService.submitReview(courseId, studentId, rating, content);
        return ApiResponse.success(review);
    }
}
