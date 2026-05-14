package com.medical.appointment.controller;

import com.medical.appointment.dto.ApiResponse;
import com.medical.appointment.entity.Patient;
import com.medical.appointment.service.PatientService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/patients")
public class PatientController {
    
    private final PatientService patientService;
    
    public PatientController(PatientService patientService) {
        this.patientService = patientService;
    }
    
    @PostMapping
    public ResponseEntity<ApiResponse<Patient>> createPatient(@RequestBody Patient patient) {
        try {
            Patient created = patientService.createPatient(patient);
            return ResponseEntity.ok(ApiResponse.success("患者创建成功", created));
        } catch (Exception e) {
            return ResponseEntity.badRequest()
                    .body(ApiResponse.badRequest("创建患者失败: " + e.getMessage()));
        }
    }
    
    @GetMapping
    public ResponseEntity<ApiResponse<List<Patient>>> getAllPatients() {
        List<Patient> patients = patientService.getAllPatients();
        return ResponseEntity.ok(ApiResponse.success(patients));
    }
    
    @GetMapping("/active")
    public ResponseEntity<ApiResponse<List<Patient>>> getActivePatients() {
        List<Patient> patients = patientService.getActivePatients();
        return ResponseEntity.ok(ApiResponse.success(patients));
    }
    
    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<Patient>> getPatientById(@PathVariable String id) {
        return patientService.getPatientById(id)
                .map(patient -> ResponseEntity.ok(ApiResponse.success(patient)))
                .orElse(ResponseEntity.status(404)
                        .body(ApiResponse.notFound("患者不存在")));
    }
    
    @GetMapping("/phone/{phone}")
    public ResponseEntity<ApiResponse<Patient>> getPatientByPhone(@PathVariable String phone) {
        return patientService.getPatientByPhone(phone)
                .map(patient -> ResponseEntity.ok(ApiResponse.success(patient)))
                .orElse(ResponseEntity.status(404)
                        .body(ApiResponse.notFound("患者不存在")));
    }
    
    @PutMapping("/{id}")
    public ResponseEntity<ApiResponse<Patient>> updatePatient(
            @PathVariable String id, @RequestBody Patient patient) {
        try {
            Patient updated = patientService.updatePatient(id, patient);
            return ResponseEntity.ok(ApiResponse.success("患者更新成功", updated));
        } catch (Exception e) {
            return ResponseEntity.status(404)
                    .body(ApiResponse.notFound("更新患者失败: " + e.getMessage()));
        }
    }
    
    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<Void>> deletePatient(@PathVariable String id) {
        patientService.deletePatient(id);
        return ResponseEntity.ok(ApiResponse.success("患者删除成功", null));
    }
    
    @PostMapping("/{id}/freeze")
    public ResponseEntity<ApiResponse<Patient>> freezePatient(@PathVariable String id) {
        try {
            Patient patient = patientService.freezePatient(id);
            return ResponseEntity.ok(ApiResponse.success("患者已冻结", patient));
        } catch (Exception e) {
            return ResponseEntity.status(404)
                    .body(ApiResponse.notFound("冻结患者失败: " + e.getMessage()));
        }
    }
    
    @PostMapping("/{id}/unfreeze")
    public ResponseEntity<ApiResponse<Patient>> unfreezePatient(@PathVariable String id) {
        try {
            Patient patient = patientService.unfreezePatient(id);
            return ResponseEntity.ok(ApiResponse.success("患者已解冻", patient));
        } catch (Exception e) {
            return ResponseEntity.status(404)
                    .body(ApiResponse.notFound("解冻患者失败: " + e.getMessage()));
        }
    }
}
