
package com.learningplatform.controller;

import com.learningplatform.dto.ApiResponse;
import com.learningplatform.entity.Certificate;
import com.learningplatform.entity.Enrollment;
import com.learningplatform.entity.Progress;
import com.learningplatform.entity.Student;
import com.learningplatform.service.CertificateService;
import com.learningplatform.service.LearningService;
import com.learningplatform.service.StudentService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/students")
public class StudentController {

    @Autowired
    private StudentService studentService;

    @Autowired
    private LearningService learningService;

    @Autowired
    private CertificateService certificateService;

    @PostMapping
    public ApiResponse<Student> createStudent(@RequestBody Student student) {
        Student saved = studentService.createStudent(student);
        return ApiResponse.success(saved);
    }

    @GetMapping
    public ApiResponse<List<Student>> getAllStudents() {
        List<Student> students = studentService.getAllStudents();
        return ApiResponse.success(students);
    }

    @GetMapping("/{studentId}")
    public ApiResponse<Student> getStudentById(@PathVariable String studentId) {
        Student student = studentService.getStudentById(studentId);
        return ApiResponse.success(student);
    }

    @PutMapping("/{studentId}")
    public ApiResponse<Student> updateStudent(@PathVariable String studentId, @RequestBody Student student) {
        Student updated = studentService.updateStudent(studentId, student);
        return ApiResponse.success(updated);
    }

    @DeleteMapping("/{studentId}")
    public ApiResponse<Void> deleteStudent(@PathVariable String studentId) {
        studentService.deleteStudent(studentId);
        return ApiResponse.success(null);
    }

    @PostMapping("/{studentId}/enroll/{courseId}")
    public ApiResponse<Enrollment> enrollCourse(@PathVariable String studentId, @PathVariable String courseId) {
        Enrollment enrollment = studentService.enrollCourse(studentId, courseId);
        return ApiResponse.success(enrollment);
    }

    @GetMapping("/{studentId}/enrollments")
    public ApiResponse<List<Enrollment>> getStudentEnrollments(@PathVariable String studentId) {
        List<Enrollment> enrollments = studentService.getStudentEnrollments(studentId);
        return ApiResponse.success(enrollments);
    }

    @GetMapping("/{studentId}/progresses")
    public ApiResponse<List<Progress>> getStudentProgresses(@PathVariable String studentId) {
        List<Progress> progresses = learningService.getStudentProgresses(studentId);
        return ApiResponse.success(progresses);
    }

    @GetMapping("/{studentId}/certificates")
    public ApiResponse<List<Certificate>> getStudentCertificates(@PathVariable String studentId) {
        List<Certificate> certificates = certificateService.getStudentCertificates(studentId);
        return ApiResponse.success(certificates);
    }
}
