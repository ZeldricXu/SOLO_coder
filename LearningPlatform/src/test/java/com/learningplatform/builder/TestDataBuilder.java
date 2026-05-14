
package com.learningplatform.builder;

import com.learningplatform.entity.*;
import com.learningplatform.util.CertificateUtil;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class TestDataBuilder {

    public static final String TEST_COURSE_ID = "course_test_001";
    public static final String TEST_STUDENT_ID = "student_test_001";
    public static final String TEST_PROGRESS_ID = "progress_test_001";
    public static final String TEST_CHAPTER_ID_1 = "chapter_test_001";
    public static final String TEST_CHAPTER_ID_2 = "chapter_test_002";
    public static final String TEST_CHAPTER_ID_3 = "chapter_test_003";
    public static final String TEST_RESOURCE_ID = "resource_test_001";
    public static final String TEST_CERTIFICATE_ID = "cert_test_001";
    public static final String TEST_REVIEW_ID = "review_test_001";
    public static final String TEST_BACKUP_ID = "backup_test_001";

    public static final String TEST_CERTIFICATE_NUMBER = "CERT20260511001";

    public static Course createDefaultCourse() {
        Course course = new Course();
        course.setCourseId(TEST_COURSE_ID);
        course.setCourseName("Java编程进阶课程");
        course.setCourseType("programming");
        course.setCourseTeacher("张教授");
        course.setCourseDuration(30);
        course.setCourseStatus("published");
        course.setCoursePrice(new BigDecimal("199.00"));
        course.setCourseDescription("深入学习Java编程，包括并发编程、JVM优化等高级主题");
        course.setCreatedAt(LocalDateTime.now().minusDays(10));
        course.setUpdatedAt(LocalDateTime.now().minusDays(5));
        return course;
    }

    public static Course createDraftCourse() {
        Course course = createDefaultCourse();
        course.setCourseId("course_draft_001");
        course.setCourseStatus("draft");
        return course;
    }

    public static Course createClosedCourse() {
        Course course = createDefaultCourse();
        course.setCourseId("course_closed_001");
        course.setCourseStatus("closed");
        return course;
    }

    public static Course createCourseWithCustomId(String courseId) {
        Course course = createDefaultCourse();
        course.setCourseId(courseId);
        return course;
    }

    public static List<Course> createMultipleCourses(int count) {
        List<Course> courses = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            Course course = createDefaultCourse();
            course.setCourseId("course_batch_" + String.format("%03d", i));
            course.setCourseName("课程编号" + i);
            courses.add(course);
        }
        return courses;
    }

    public static Student createDefaultStudent() {
        Student student = new Student();
        student.setStudentId(TEST_STUDENT_ID);
        student.setStudentName("测试学员");
        student.setStudentPhone("13800138001");
        student.setStudentEmail("test.student@example.com");
        student.setCoursesEnrolled(5);
        student.setCoursesCompleted(3);
        student.setCertificatesEarned(2);
        student.setStudentStatus("active");
        student.setRegisteredAt(LocalDateTime.now().minusMonths(3));
        return student;
    }

    public static Student createNewStudent() {
        Student student = new Student();
        student.setStudentId("student_new_001");
        student.setStudentName("新学员");
        student.setStudentPhone("13800138002");
        student.setStudentEmail("new.student@example.com");
        student.setCoursesEnrolled(0);
        student.setCoursesCompleted(0);
        student.setCertificatesEarned(0);
        student.setStudentStatus("active");
        return student;
    }

    public static Student createStudentWithCustomId(String studentId) {
        Student student = createDefaultStudent();
        student.setStudentId(studentId);
        return student;
    }

    public static List<Student> createMultipleStudents(int count) {
        List<Student> students = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            Student student = createDefaultStudent();
            student.setStudentId("student_batch_" + String.format("%03d", i));
            student.setStudentName("学员" + i);
            students.add(student);
        }
        return students;
    }

    public static Chapter createDefaultChapter() {
        Chapter chapter = new Chapter();
        chapter.setChapterId(TEST_CHAPTER_ID_1);
        chapter.setCourseId(TEST_COURSE_ID);
        chapter.setChapterName("第一章：Java基础回顾");
        chapter.setChapterOrder(1);
        chapter.setChapterDuration(2);
        chapter.setChapterStatus("published");
        chapter.setChapterDescription("回顾Java语言的基础知识");
        return chapter;
    }

    public static Chapter createChapter(int order, String chapterId) {
        Chapter chapter = createDefaultChapter();
        chapter.setChapterId(chapterId);
        chapter.setChapterOrder(order);
        chapter.setChapterName("第" + order + "章：测试章节");
        return chapter;
    }

    public static List<Chapter> createMultipleChapters(String courseId, int count) {
        List<Chapter> chapters = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            Chapter chapter = createDefaultChapter();
            chapter.setChapterId("chapter_" + courseId + "_" + i);
            chapter.setCourseId(courseId);
            chapter.setChapterOrder(i);
            chapter.setChapterName("第" + i + "章");
            chapters.add(chapter);
        }
        return chapters;
    }

    public static Progress createDefaultProgress() {
        Progress progress = new Progress();
        progress.setProgressId(TEST_PROGRESS_ID);
        progress.setCourseId(TEST_COURSE_ID);
        progress.setStudentId(TEST_STUDENT_ID);
        progress.setProgressStatus("in_progress");
        progress.setProgressPercent(33);
        progress.setChaptersCompleted(1);
        progress.setTotalChapters(3);
        progress.setLearningTime(120L);
        progress.setStartedAt(LocalDateTime.now().minusDays(3));
        progress.setUpdatedAt(LocalDateTime.now().minusHours(2));
        return progress;
    }

    public static Progress createCompletedProgress() {
        Progress progress = createDefaultProgress();
        progress.setProgressStatus("completed");
        progress.setProgressPercent(100);
        progress.setChaptersCompleted(3);
        progress.setCompletedAt(LocalDateTime.now().minusHours(1));
        return progress;
    }

    public static Progress createProgressWithCustomPercent(int percent, int chaptersCompleted, int totalChapters) {
        Progress progress = createDefaultProgress();
        progress.setProgressPercent(percent);
        progress.setChaptersCompleted(chaptersCompleted);
        progress.setTotalChapters(totalChapters);
        return progress;
    }

    public static List<Progress> createMultipleProgresses(int count) {
        List<Progress> progresses = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            Progress progress = createDefaultProgress();
            progress.setProgressId("progress_batch_" + String.format("%03d", i));
            progress.setProgressPercent(i * 10);
            progresses.add(progress);
        }
        return progresses;
    }

    public static ChapterProgress createDefaultChapterProgress() {
        ChapterProgress chapterProgress = new ChapterProgress();
        chapterProgress.setChapterProgressId("chprog_test_001");
        chapterProgress.setProgressId(TEST_PROGRESS_ID);
        chapterProgress.setCourseId(TEST_COURSE_ID);
        chapterProgress.setStudentId(TEST_STUDENT_ID);
        chapterProgress.setChapterId(TEST_CHAPTER_ID_1);
        chapterProgress.setIsCompleted(false);
        chapterProgress.setLearningTime(30L);
        return chapterProgress;
    }

    public static ChapterProgress createCompletedChapterProgress() {
        ChapterProgress chapterProgress = createDefaultChapterProgress();
        chapterProgress.setIsCompleted(true);
        chapterProgress.setCompletedAt(LocalDateTime.now().minusHours(1));
        return chapterProgress;
    }

    public static List<ChapterProgress> createChapterProgressList(String progressId, int count, int completedCount) {
        List<ChapterProgress> chapterProgresses = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            ChapterProgress cp = createDefaultChapterProgress();
            cp.setChapterProgressId("chprog_" + progressId + "_" + i);
            cp.setProgressId(progressId);
            cp.setChapterId("chapter_" + i);
            cp.setIsCompleted(i <= completedCount);
            if (i <= completedCount) {
                cp.setCompletedAt(LocalDateTime.now().minusHours(i));
            }
            chapterProgresses.add(cp);
        }
        return chapterProgresses;
    }

    public static Certificate createDefaultCertificate() {
        Certificate certificate = new Certificate();
        certificate.setCertificateId(TEST_CERTIFICATE_ID);
        certificate.setCourseId(TEST_COURSE_ID);
        certificate.setStudentId(TEST_STUDENT_ID);
        certificate.setCertificateType(CertificateUtil.CERT_TYPE_COMPLETION);
        certificate.setCertificateNumber(TEST_CERTIFICATE_NUMBER);
        certificate.setCertificateStatus("valid");
        certificate.setDigitalSignature("test_signature_" + UUID.randomUUID().toString());
        certificate.setIssuedAt(LocalDateTime.now().minusDays(1));
        certificate.setValidUntil(LocalDateTime.now().plusYears(3));
        return certificate;
    }

    public static Certificate createProfessionalCertificate() {
        Certificate certificate = createDefaultCertificate();
        certificate.setCertificateId("cert_pro_001");
        certificate.setCertificateType(CertificateUtil.CERT_TYPE_PROFESSIONAL);
        certificate.setValidUntil(LocalDateTime.now().plusYears(10));
        return certificate;
    }

    public static Certificate createExcellenceCertificate() {
        Certificate certificate = createDefaultCertificate();
        certificate.setCertificateId("cert_exc_001");
        certificate.setCertificateType(CertificateUtil.CERT_TYPE_EXCELLENCE);
        certificate.setValidUntil(LocalDateTime.now().plusYears(5));
        return certificate;
    }

    public static Certificate createRevokedCertificate() {
        Certificate certificate = createDefaultCertificate();
        certificate.setCertificateId("cert_rev_001");
        certificate.setCertificateStatus("revoked");
        return certificate;
    }

    public static Certificate createExpiredCertificate() {
        Certificate certificate = createDefaultCertificate();
        certificate.setCertificateId("cert_exp_001");
        certificate.setIssuedAt(LocalDateTime.now().minusYears(4));
        certificate.setValidUntil(LocalDateTime.now().minusYears(1));
        return certificate;
    }

    public static List<Certificate> createMultipleCertificates(int count) {
        List<Certificate> certificates = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            Certificate cert = createDefaultCertificate();
            cert.setCertificateId("cert_batch_" + String.format("%03d", i));
            cert.setCertificateNumber("CERT20260511" + String.format("%03d", i));
            certificates.add(cert);
        }
        return certificates;
    }

    public static Review createDefaultReview() {
        Review review = new Review();
        review.setReviewId(TEST_REVIEW_ID);
        review.setCourseId(TEST_COURSE_ID);
        review.setStudentId(TEST_STUDENT_ID);
        review.setReviewRating(5);
        review.setReviewContent("非常棒的课程，讲解清晰，内容丰富！");
        review.setReviewStatus("published");
        review.setReviewTime(LocalDateTime.now().minusDays(2));
        return review;
    }

    public static Review createReviewWithRating(int rating) {
        Review review = createDefaultReview();
        review.setReviewRating(rating);
        return review;
    }

    public static List<Review> createMultipleReviews(String courseId, int count) {
        List<Review> reviews = new ArrayList<>();
        int[] ratings = {5, 4, 5, 3, 4, 5, 4, 5, 4, 3};
        String[] contents = {
            "课程很棒！",
            "内容丰富",
            "讲解清晰",
            "一般般",
            "值得学习",
            "推荐学习",
            "老师讲得好",
            "收获很大",
            "还不错",
            "需要更深入"
        };
        for (int i = 0; i < count && i < ratings.length; i++) {
            Review review = createDefaultReview();
            review.setReviewId("review_" + courseId + "_" + i);
            review.setCourseId(courseId);
            review.setStudentId("student_review_" + i);
            review.setReviewRating(ratings[i]);
            review.setReviewContent(contents[i]);
            reviews.add(review);
        }
        return reviews;
    }

    public static Resource createDefaultResource() {
        Resource resource = new Resource();
        resource.setResourceId(TEST_RESOURCE_ID);
        resource.setCourseId(TEST_COURSE_ID);
        resource.setChapterId(TEST_CHAPTER_ID_1);
        resource.setResourceName("Java编程基础讲义.pdf");
        resource.setResourceType("document");
        resource.setResourceUrl("/resources/course_001/lecture1.pdf");
        resource.setResourcePath("/uploads/resources/course_001/lecture1.pdf");
        resource.setResourceSize(1024L * 1024L * 5);
        resource.setResourceStatus("active");
        resource.setUploadedBy("teacher_001");
        return resource;
    }

    public static Resource createVideoResource() {
        Resource resource = createDefaultResource();
        resource.setResourceId("resource_video_001");
        resource.setResourceName("第一章视频讲解.mp4");
        resource.setResourceType("video");
        resource.setResourceSize(1024L * 1024L * 100);
        return resource;
    }

    public static List<Resource> createMultipleResources(String courseId, int count) {
        List<Resource> resources = new ArrayList<>();
        String[] types = {"document", "video", "audio", "image"};
        for (int i = 1; i <= count; i++) {
            Resource resource = createDefaultResource();
            resource.setResourceId("resource_" + courseId + "_" + i);
            resource.setCourseId(courseId);
            resource.setResourceType(types[i % types.length]);
            resource.setResourceName("资源文件" + i);
            resources.add(resource);
        }
        return resources;
    }

    public static ProgressBackup createDefaultBackup() {
        ProgressBackup backup = new ProgressBackup();
        backup.setBackupId(TEST_BACKUP_ID);
        backup.setProgressId(TEST_PROGRESS_ID);
        backup.setCourseId(TEST_COURSE_ID);
        backup.setStudentId(TEST_STUDENT_ID);
        backup.setProgressStatus("in_progress");
        backup.setProgressPercent(50);
        backup.setChaptersCompleted(2);
        backup.setTotalChapters(4);
        backup.setLearningTime(180L);
        backup.setBackupReason("manual");
        backup.setBackupLevel("medium");
        backup.setBackupTime(LocalDateTime.now().minusMinutes(30));
        backup.setIsVerified(true);
        return backup;
    }

    public static ProgressBackup createHighLevelBackup() {
        ProgressBackup backup = createDefaultBackup();
        backup.setBackupId("backup_high_001");
        backup.setBackupLevel("high");
        return backup;
    }

    public static ProgressBackup createLowLevelBackup() {
        ProgressBackup backup = createDefaultBackup();
        backup.setBackupId("backup_low_001");
        backup.setBackupLevel("low");
        return backup;
    }

    public static List<ProgressBackup> createMultipleBackups(String progressId, int count) {
        List<ProgressBackup> backups = new ArrayList<>();
        String[] reasons = {"scheduled", "manual", "auto", "scheduled", "manual"};
        String[] levels = {"low", "medium", "high", "medium", "low"};
        for (int i = 1; i <= count && i <= reasons.length; i++) {
            ProgressBackup backup = createDefaultBackup();
            backup.setBackupId("backup_" + progressId + "_" + i);
            backup.setProgressId(progressId);
            backup.setProgressPercent(i * 20);
            backup.setBackupReason(reasons[i - 1]);
            backup.setBackupLevel(levels[i - 1]);
            backup.setBackupTime(LocalDateTime.now().minusHours(i));
            backups.add(backup);
        }
        return backups;
    }

    public static Enrollment createDefaultEnrollment() {
        Enrollment enrollment = new Enrollment();
        enrollment.setEnrollmentId("enroll_test_001");
        enrollment.setCourseId(TEST_COURSE_ID);
        enrollment.setStudentId(TEST_STUDENT_ID);
        enrollment.setEnrollmentStatus("active");
        enrollment.setEnrolledAt(LocalDateTime.now().minusDays(5));
        return enrollment;
    }

    public static Statistics createDefaultStatistics() {
        Statistics stats = new Statistics();
        stats.setStatId("stat_test_001");
        stats.setStatMonth("2026-05");
        stats.setCourseCount(50);
        stats.setStudentCount(1000);
        stats.setEnrollmentCount(500);
        stats.setCompletionCount(300);
        stats.setCertificateCount(250);
        stats.setReviewCount(400);
        stats.setAverageRating(new BigDecimal("4.5"));
        return stats;
    }

    public static LearningHistory createDefaultHistory() {
        LearningHistory history = new LearningHistory();
        history.setHistoryId("history_test_001");
        history.setStudentId(TEST_STUDENT_ID);
        history.setCourseId(TEST_COURSE_ID);
        history.setChapterId(TEST_CHAPTER_ID_1);
        history.setHistoryType("learning");
        history.setHistoryAction("start_course");
        history.setHistoryDetail("开始学习课程");
        history.setCreatedAt(LocalDateTime.now().minusHours(2));
        return history;
    }

    public static List<LearningHistory> createLearningHistoryList(String studentId, int count) {
        List<LearningHistory> histories = new ArrayList<>();
        String[] actions = {"start_course", "update_progress", "complete_chapter", "update_progress", "complete_course"};
        for (int i = 0; i < count && i < actions.length; i++) {
            LearningHistory history = createDefaultHistory();
            history.setHistoryId("history_" + studentId + "_" + i);
            history.setStudentId(studentId);
            history.setHistoryAction(actions[i]);
            history.setCreatedAt(LocalDateTime.now().minusHours(count - i));
            histories.add(history);
        }
        return histories;
    }

    public static String generateUniqueId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }

    public static LocalDateTime getPastTime(int minutes) {
        return LocalDateTime.now().minusMinutes(minutes);
    }

    public static LocalDateTime getFutureTime(int minutes) {
        return LocalDateTime.now().plusMinutes(minutes);
    }
}
