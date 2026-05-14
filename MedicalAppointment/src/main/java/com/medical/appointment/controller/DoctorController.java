package com.medical.appointment.controller;

import com.medical.appointment.dto.ApiResponse;
import com.medical.appointment.entity.Doctor;
import com.medical.appointment.service.DoctorService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/doctors")
public class DoctorController {
    
    private final DoctorService doctorService;
    
    public DoctorController(DoctorService doctorService) {
        this.doctorService = doctorService;
    }
    
    @PostMapping
    public ResponseEntity<ApiResponse<Doctor>> createDoctor(@RequestBody Doctor doctor) {
        try {
            Doctor created = doctorService.createDoctor(doctor);
            return ResponseEntity.ok(ApiResponse.success("医生创建成功", created));
        } catch (Exception e) {
            return ResponseEntity.badRequest()
                    .body(ApiResponse.badRequest("创建医生失败: " + e.getMessage()));
        }
    }
    
    @GetMapping
    public ResponseEntity<ApiResponse<List<Doctor>>> getAllDoctors() {
        List<Doctor> doctors = doctorService.getAllDoctors();
        return ResponseEntity.ok(ApiResponse.success(doctors));
    }
    
    @GetMapping("/active")
    public ResponseEntity<ApiResponse<List<Doctor>>> getActiveDoctors() {
        List<Doctor> doctors = doctorService.getActiveDoctors();
        return ResponseEntity.ok(ApiResponse.success(doctors));
    }
    
    @GetMapping("/department/{departmentId}")
    public ResponseEntity<ApiResponse<List<Doctor>>> getDoctorsByDepartment(
            @PathVariable String departmentId) {
        List<Doctor> doctors = doctorService.getDoctorsByDepartment(departmentId);
        return ResponseEntity.ok(ApiResponse.success(doctors));
    }
    
    @GetMapping("/department/{departmentId}/active")
    public ResponseEntity<ApiResponse<List<Doctor>>> getActiveDoctorsByDepartment(
            @PathVariable String departmentId) {
        List<Doctor> doctors = doctorService.getActiveDoctorsByDepartment(departmentId);
        return ResponseEntity.ok(ApiResponse.success(doctors));
    }
    
    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<Doctor>> getDoctorById(@PathVariable String id) {
        return doctorService.getDoctorById(id)
                .map(doctor -> ResponseEntity.ok(ApiResponse.success(doctor)))
                .orElse(ResponseEntity.status(404)
                        .body(ApiResponse.notFound("医生不存在")));
    }
    
    @PutMapping("/{id}")
    public ResponseEntity<ApiResponse<Doctor>> updateDoctor(
            @PathVariable String id, @RequestBody Doctor doctor) {
        try {
            Doctor updated = doctorService.updateDoctor(id, doctor);
            return ResponseEntity.ok(ApiResponse.success("医生更新成功", updated));
        } catch (Exception e) {
            return ResponseEntity.status(404)
                    .body(ApiResponse.notFound("更新医生失败: " + e.getMessage()));
        }
    }
    
    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<Void>> deleteDoctor(@PathVariable String id) {
        doctorService.deleteDoctor(id);
        return ResponseEntity.ok(ApiResponse.success("医生删除成功", null));
    }
}
