
package com.learningplatform.config;

import com.learningplatform.entity.Chapter;
import com.learningplatform.entity.Course;
import com.learningplatform.entity.Student;
import com.learningplatform.service.ChapterService;
import com.learningplatform.service.CourseService;
import com.learningplatform.service.StudentService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

@Component
@Profile("!test")
public class DataInitializer implements CommandLineRunner {

    private static final Logger logger = LoggerFactory.getLogger(DataInitializer.class);

    @Autowired
    private CourseService courseService;

    @Autowired
    private ChapterService chapterService;

    @Autowired
    private StudentService studentService;

    @Override
    public void run(String... args) {
        if (courseService.getAllCourses().isEmpty()) {
            initSampleData();
        }
    }

    private void initSampleData() {
        logger.info("开始初始化示例数据...");

        Course course = new Course();
        course.setCourseId("course_001");
        course.setCourseName("Java编程课程");
        course.setCourseType("programming");
        course.setCourseTeacher("张老师");
        course.setCourseDuration(30);
        course.setCourseStatus("published");
        course.setCoursePrice(new BigDecimal("100.00"));
        course.setCourseDescription("这是一门全面的Java编程课程，适合初学者和中级开发者。");
        courseService.createCourse(course);

        Chapter chapter1 = new Chapter();
        chapter1.setChapterId("chapter_001");
        chapter1.setCourseId("course_001");
        chapter1.setChapterName("Java基础入门");
        chapter1.setChapterOrder(1);
        chapter1.setChapterDuration(2);
        chapter1.setChapterStatus("published");
        chapter1.setChapterDescription("学习Java的基本语法和概念");
        chapterService.createChapter(chapter1);

        Chapter chapter2 = new Chapter();
        chapter2.setChapterId("chapter_002");
        chapter2.setCourseId("course_001");
        chapter2.setChapterName("面向对象编程");
        chapter2.setChapterOrder(2);
        chapter2.setChapterDuration(3);
        chapter2.setChapterStatus("published");
        chapter2.setChapterDescription("学习Java的面向对象特性");
        chapterService.createChapter(chapter2);

        Chapter chapter3 = new Chapter();
        chapter3.setChapterId("chapter_003");
        chapter3.setCourseId("course_001");
        chapter3.setChapterName("Java高级特性");
        chapter3.setChapterOrder(3);
        chapter3.setChapterDuration(3);
        chapter3.setChapterStatus("published");
        chapter3.setChapterDescription("学习Java的高级特性和框架");
        chapterService.createChapter(chapter3);

        Course course2 = new Course();
        course2.setCourseId("course_002");
        course2.setCourseName("Web开发课程");
        course2.setCourseType("web");
        course2.setCourseTeacher("李老师");
        course2.setCourseDuration(20);
        course2.setCourseStatus("published");
        course2.setCoursePrice(new BigDecimal("150.00"));
        course2.setCourseDescription("学习现代Web开发技术栈");
        courseService.createCourse(course2);

        Chapter chapter4 = new Chapter();
        chapter4.setChapterId("chapter_004");
        chapter4.setCourseId("course_002");
        chapter4.setChapterName("HTML/CSS基础");
        chapter4.setChapterOrder(1);
        chapter4.setChapterDuration(2);
        chapter4.setChapterStatus("published");
        chapterService.createChapter(chapter4);

        Chapter chapter5 = new Chapter();
        chapter5.setChapterId("chapter_005");
        chapter5.setCourseId("course_002");
        chapter5.setChapterName("JavaScript编程");
        chapter5.setChapterOrder(2);
        chapter5.setChapterDuration(3);
        chapter5.setChapterStatus("published");
        chapterService.createChapter(chapter5);

        Student student = new Student();
        student.setStudentId("student_001");
        student.setStudentName("学员小王");
        student.setStudentPhone("13800138001");
        student.setStudentEmail("student1@example.com");
        student.setStudentStatus("active");
        studentService.createStudent(student);

        Student student2 = new Student();
        student2.setStudentId("student_002");
        student2.setStudentName("学员小李");
        student2.setStudentPhone("13800138002");
        student2.setStudentEmail("student2@example.com");
        student2.setStudentStatus("active");
        studentService.createStudent(student2);

        logger.info("示例数据初始化完成！");
        logger.info("========================================");
        logger.info("可用课程:");
        logger.info("  - course_001: Java编程课程 (3个章节)");
        logger.info("  - course_002: Web开发课程 (2个章节)");
        logger.info("可用学员:");
        logger.info("  - student_001: 学员小王");
        logger.info("  - student_002: 学员小李");
        logger.info("========================================");
        logger.info("测试API示例:");
        logger.info("1. 开始学习: POST /api/v1/learning/start");
        logger.info("   Body: {\"course_id\":\"course_001\",\"student_id\":\"student_001\"}");
        logger.info("2. 更新进度: POST /api/v1/learning/update");
        logger.info("   Body: {\"progress_id\":\"xxx\",\"chapter_id\":\"chapter_001\",\"completed\":true}");
        logger.info("3. 生成证书: POST /api/v1/certificates/generate");
        logger.info("   Body: {\"course_id\":\"course_001\",\"student_id\":\"student_001\"}");
        logger.info("========================================");
    }
}
