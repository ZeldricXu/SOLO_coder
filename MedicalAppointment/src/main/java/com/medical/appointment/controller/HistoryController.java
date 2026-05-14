package com.medical.appointment.controller;

import com.medical.appointment.dto.ApiResponse;
import com.medical.appointment.entity.AppointmentHistory;
import com.medical.appointment.service.HistoryService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/history")
public class HistoryController {
    
    private final HistoryService historyService;
    
    public HistoryController(HistoryService historyService) {
        this.historyService = historyService;
    }
    
    @GetMapping
    public ResponseEntity<ApiResponse<List<AppointmentHistory>>> getAllHistory() {
        List<AppointmentHistory> history = historyService.getAllHistory();
        return ResponseEntity.ok(ApiResponse.success(history));
    }
    
    @GetMapping("/appointment/{appointmentId}")
    public ResponseEntity<ApiResponse<List<AppointmentHistory>>> getHistoryByAppointment(
            @PathVariable String appointmentId) {
        List<AppointmentHistory> history = historyService.getHistoryByAppointment(appointmentId);
        return ResponseEntity.ok(ApiResponse.success(history));
    }
    
    @GetMapping("/action/{actionType}")
    public ResponseEntity<ApiResponse<List<AppointmentHistory>>> getHistoryByActionType(
            @PathVariable String actionType) {
        List<AppointmentHistory> history = historyService.getHistoryByActionType(actionType);
        return ResponseEntity.ok(ApiResponse.success(history));
    }
}
