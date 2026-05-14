
package com.learningplatform.service;

import com.learningplatform.entity.Enrollment;
import com.learningplatform.entity.Student;
import com.learningplatform.exception.BusinessException;
import com.learningplatform.repository.EnrollmentRepository;
import com.learningplatform.repository.StudentRepository;
import com.learningplatform.util.IdGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Service
public class StudentService {

    private static final Logger logger = LoggerFactory.getLogger(StudentService.class);

    @Autowired
    private StudentRepository studentRepository;

    @Autowired
    private EnrollmentRepository enrollmentRepository;

    @Autowired
    private CourseService courseService;

    @Transactional
    public Student createStudent(Student student) {
        if (student.getStudentId() == null || student.getStudentId().isEmpty()) {
            student.setStudentId(IdGenerator.generateStudentId());
        }
        if (student.getStudentStatus() == null) {
            student.setStudentStatus("active");
        }
        Student saved = studentRepository.save(student);
        logger.info("创建学员成功: {}", saved.getStudentId());
        return saved;
    }

    @Transactional
    public Student updateStudent(String studentId, Student student) {
        Student existing = getStudentById(studentId);
        if (student.getStudentName() != null) {
            existing.setStudentName(student.getStudentName());
        }
        if (student.getStudentPhone() != null) {
            existing.setStudentPhone(student.getStudentPhone());
        }
        if (student.getStudentEmail() != null) {
            existing.setStudentEmail(student.getStudentEmail());
        }
        if (student.getStudentStatus() != null) {
            existing.setStudentStatus(student.getStudentStatus());
        }
        Student saved = studentRepository.save(existing);
        logger.info("更新学员成功: {}", studentId);
        return saved;
    }

    public Student getStudentById(String studentId) {
        return studentRepository.findById(studentId)
                .orElseThrow(() -> new BusinessException(404, "学员不存在: " + studentId));
    }

    public Optional<Student> findStudentById(String studentId) {
        return studentRepository.findById(studentId);
    }

    public List<Student> getAllStudents() {
        return studentRepository.findAll();
    }

    @Transactional
    public void deleteStudent(String studentId) {
        Student student = getStudentById(studentId);
        studentRepository.delete(student);
        logger.info("删除学员成功: {}", studentId);
    }

    @Transactional
    public Enrollment enrollCourse(String studentId, String courseId) {
        Student student = getStudentById(studentId);
        courseService.getCourseById(courseId);

        if (enrollmentRepository.existsByCourseIdAndStudentId(courseId, studentId)) {
            return enrollmentRepository.findByCourseIdAndStudentId(courseId, studentId).get();
        }

        Enrollment enrollment = new Enrollment();
        enrollment.setEnrollmentId(IdGenerator.generateEnrollmentId());
        enrollment.setCourseId(courseId);
        enrollment.setStudentId(studentId);
        enrollment.setEnrollmentStatus("active");

        Enrollment saved = enrollmentRepository.save(enrollment);

        student.setCoursesEnrolled(student.getCoursesEnrolled() + 1);
        studentRepository.save(student);

        logger.info("学员注册课程成功: student={}, course={}", studentId, courseId);
        return saved;
    }

    public boolean isEnrolled(String studentId, String courseId) {
        return enrollmentRepository.existsByCourseIdAndStudentId(courseId, studentId);
    }

    public Optional<Enrollment> getEnrollment(String studentId, String courseId) {
        return enrollmentRepository.findByCourseIdAndStudentId(courseId, studentId);
    }

    public List<Enrollment> getStudentEnrollments(String studentId) {
        return enrollmentRepository.findByStudentId(studentId);
    }

    public List<Enrollment> getCourseEnrollments(String courseId) {
        return enrollmentRepository.findByCourseId(courseId);
    }

    @Transactional
    public void incrementCompletedCourses(String studentId) {
        Student student = getStudentById(studentId);
        student.setCoursesCompleted(student.getCoursesCompleted() + 1);
        studentRepository.save(student);
        logger.info("学员完成课程数+1: {}", studentId);
    }

    @Transactional
    public void incrementCertificatesEarned(String studentId) {
        Student student = getStudentById(studentId);
        student.setCertificatesEarned(student.getCertificatesEarned() + 1);
        studentRepository.save(student);
        logger.info("学员证书数+1: {}", studentId);
    }

    public long getTotalStudentCount() {
        return studentRepository.count();
    }
}
