package com.medical.appointment.controller;

import com.medical.appointment.dto.ApiResponse;
import com.medical.appointment.dto.VisitRequest;
import com.medical.appointment.dto.VisitResult;
import com.medical.appointment.entity.Visit;
import com.medical.appointment.service.VisitService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/visits")
public class VisitController {
    
    private final VisitService visitService;
    
    public VisitController(VisitService visitService) {
        this.visitService = visitService;
    }
    
    @PostMapping("/register")
    public ResponseEntity<ApiResponse<Map<String, Object>>> registerVisit(
            @RequestBody VisitRequest request) {
        try {
            VisitResult result = visitService.registerVisit(
                    request.getAppointmentId(),
                    request.getVisitRecord(),
                    request.getVisitDiagnosis(),
                    request.getVisitPrescription());
            Map<String, Object> data = new HashMap<>();
            data.put("visit_id", result.getVisitId());
            data.put("status", result.getStatus());
            data.put("visit_time", result.getVisitTime());
            data.put("patient_name", result.getPatientName());
            data.put("doctor_name", result.getDoctorName());
            return ResponseEntity.ok(ApiResponse.success("就诊登记成功", data));
        } catch (Exception e) {
            return ResponseEntity.badRequest()
                    .body(ApiResponse.badRequest("就诊登记失败: " + e.getMessage()));
        }
    }
    
    @GetMapping
    public ResponseEntity<ApiResponse<List<Visit>>> getAllVisits() {
        List<Visit> visits = visitService.getAllVisits();
        return ResponseEntity.ok(ApiResponse.success(visits));
    }
    
    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<Visit>> getVisitById(@PathVariable String id) {
        return visitService.getVisitById(id)
                .map(visit -> ResponseEntity.ok(ApiResponse.success(visit)))
                .orElse(ResponseEntity.status(404)
                        .body(ApiResponse.notFound("就诊记录不存在")));
    }
    
    @GetMapping("/appointment/{appointmentId}")
    public ResponseEntity<ApiResponse<Visit>> getVisitByAppointmentId(@PathVariable String appointmentId) {
        return visitService.getVisitByAppointmentId(appointmentId)
                .map(visit -> ResponseEntity.ok(ApiResponse.success(visit)))
                .orElse(ResponseEntity.status(404)
                        .body(ApiResponse.notFound("就诊记录不存在")));
    }
    
    @GetMapping("/patient/{patientId}")
    public ResponseEntity<ApiResponse<List<Visit>>> getVisitsByPatient(@PathVariable String patientId) {
        List<Visit> visits = visitService.getVisitsByPatient(patientId);
        return ResponseEntity.ok(ApiResponse.success(visits));
    }
    
    @GetMapping("/doctor/{doctorId}")
    public ResponseEntity<ApiResponse<List<Visit>>> getVisitsByDoctor(@PathVariable String doctorId) {
        List<Visit> visits = visitService.getVisitsByDoctor(doctorId);
        return ResponseEntity.ok(ApiResponse.success(visits));
    }
    
    @PutMapping("/{id}")
    public ResponseEntity<ApiResponse<Visit>> updateVisit(
            @PathVariable String id, @RequestBody Visit visit) {
        try {
            Visit updated = visitService.updateVisit(id, visit);
            return ResponseEntity.ok(ApiResponse.success("就诊记录更新成功", updated));
        } catch (Exception e) {
            return ResponseEntity.status(404)
                    .body(ApiResponse.notFound("更新就诊记录失败: " + e.getMessage()));
        }
    }
}
