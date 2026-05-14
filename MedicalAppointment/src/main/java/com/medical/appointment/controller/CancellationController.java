package com.medical.appointment.controller;

import com.medical.appointment.dto.ApiResponse;
import com.medical.appointment.dto.CancelRequest;
import com.medical.appointment.service.CancellationService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/cancellations")
public class CancellationController {
    
    private final CancellationService cancellationService;
    
    public CancellationController(CancellationService cancellationService) {
        this.cancellationService = cancellationService;
    }
    
    @PostMapping
    public ResponseEntity<ApiResponse<Map<String, Object>>> cancelAppointment(
            @RequestBody CancelRequest request) {
        try {
            String appointmentId = cancellationService.cancelAppointment(
                    request.getAppointmentId(), request.getCancelReason());
            Map<String, Object> data = new HashMap<>();
            data.put("appointment_id", appointmentId);
            data.put("status", "cancelled");
            return ResponseEntity.ok(ApiResponse.success("取消成功", data));
        } catch (Exception e) {
            return ResponseEntity.badRequest()
                    .body(ApiResponse.badRequest("取消失败: " + e.getMessage()));
        }
    }
}
