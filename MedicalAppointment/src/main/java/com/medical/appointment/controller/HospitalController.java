package com.medical.appointment.controller;

import com.medical.appointment.dto.ApiResponse;
import com.medical.appointment.entity.Hospital;
import com.medical.appointment.service.HospitalService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/hospitals")
public class HospitalController {
    
    private final HospitalService hospitalService;
    
    public HospitalController(HospitalService hospitalService) {
        this.hospitalService = hospitalService;
    }
    
    @PostMapping
    public ResponseEntity<ApiResponse<Hospital>> createHospital(@RequestBody Hospital hospital) {
        try {
            Hospital created = hospitalService.createHospital(hospital);
            return ResponseEntity.ok(ApiResponse.success("医院创建成功", created));
        } catch (Exception e) {
            return ResponseEntity.badRequest()
                    .body(ApiResponse.badRequest("创建医院失败: " + e.getMessage()));
        }
    }
    
    @GetMapping
    public ResponseEntity<ApiResponse<List<Hospital>>> getAllHospitals() {
        List<Hospital> hospitals = hospitalService.getAllHospitals();
        return ResponseEntity.ok(ApiResponse.success(hospitals));
    }
    
    @GetMapping("/active")
    public ResponseEntity<ApiResponse<List<Hospital>>> getActiveHospitals() {
        List<Hospital> hospitals = hospitalService.getActiveHospitals();
        return ResponseEntity.ok(ApiResponse.success(hospitals));
    }
    
    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<Hospital>> getHospitalById(@PathVariable String id) {
        return hospitalService.getHospitalById(id)
                .map(hospital -> ResponseEntity.ok(ApiResponse.success(hospital)))
                .orElse(ResponseEntity.status(404)
                        .body(ApiResponse.notFound("医院不存在")));
    }
    
    @PutMapping("/{id}")
    public ResponseEntity<ApiResponse<Hospital>> updateHospital(
            @PathVariable String id, @RequestBody Hospital hospital) {
        try {
            Hospital updated = hospitalService.updateHospital(id, hospital);
            return ResponseEntity.ok(ApiResponse.success("医院更新成功", updated));
        } catch (Exception e) {
            return ResponseEntity.status(404)
                    .body(ApiResponse.notFound("更新医院失败: " + e.getMessage()));
        }
    }
    
    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<Void>> deleteHospital(@PathVariable String id) {
        hospitalService.deleteHospital(id);
        return ResponseEntity.ok(ApiResponse.success("医院删除成功", null));
    }
    
    @PostMapping("/{id}/activate")
    public ResponseEntity<ApiResponse<Hospital>> activateHospital(@PathVariable String id) {
        try {
            Hospital hospital = hospitalService.activateHospital(id);
            return ResponseEntity.ok(ApiResponse.success("医院已激活", hospital));
        } catch (Exception e) {
            return ResponseEntity.status(404)
                    .body(ApiResponse.notFound("激活医院失败: " + e.getMessage()));
        }
    }
    
    @PostMapping("/{id}/deactivate")
    public ResponseEntity<ApiResponse<Hospital>> deactivateHospital(@PathVariable String id) {
        try {
            Hospital hospital = hospitalService.deactivateHospital(id);
            return ResponseEntity.ok(ApiResponse.success("医院已停用", hospital));
        } catch (Exception e) {
            return ResponseEntity.status(404)
                    .body(ApiResponse.notFound("停用医院失败: " + e.getMessage()));
        }
    }
}
