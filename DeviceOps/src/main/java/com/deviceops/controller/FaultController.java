package com.deviceops.controller;

import com.deviceops.dto.ApiResponse;
import com.deviceops.dto.FaultReportRequest;
import com.deviceops.entity.FaultRecord;
import com.deviceops.service.fault.FaultService;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/faults")
public class FaultController {

    @Autowired
    private FaultService faultService;

    @PostMapping("/report")
    public ApiResponse<Map<String, String>> reportFault(@Valid @RequestBody FaultReportRequest request) {
        FaultRecord fault = faultService.reportFault(request);
        
        Map<String, String> result = new HashMap<>();
        result.put("fault_id", fault.getFaultId());
        result.put("status", fault.getFaultStatus());
        
        return ApiResponse.success(result);
    }

    @GetMapping("/{faultId}")
    public ApiResponse<FaultRecord> getFault(@PathVariable String faultId) {
        FaultRecord fault = faultService.getFault(faultId);
        return ApiResponse.success(fault);
    }

    @GetMapping
    public ApiResponse<List<FaultRecord>> getAllFaults() {
        return ApiResponse.success(faultService.getAllFaults());
    }

    @GetMapping("/device/{deviceId}")
    public ApiResponse<List<FaultRecord>> getFaultsByDevice(@PathVariable String deviceId) {
        return ApiResponse.success(faultService.getFaultsByDevice(deviceId));
    }

    @GetMapping("/status/{status}")
    public ApiResponse<List<FaultRecord>> getFaultsByStatus(@PathVariable String status) {
        return ApiResponse.success(faultService.getFaultsByStatus(status));
    }

    @PutMapping("/{faultId}/process")
    public ApiResponse<FaultRecord> processFault(@PathVariable String faultId) {
        FaultRecord fault = faultService.processFault(faultId);
        return ApiResponse.success(fault);
    }

    @PutMapping("/{faultId}/resolve")
    public ApiResponse<FaultRecord> resolveFault(@PathVariable String faultId, 
                                                 @RequestParam(required = false) String operatorId) {
        FaultRecord fault = faultService.resolveFault(faultId, operatorId);
        return ApiResponse.success(fault);
    }

    @PutMapping("/{faultId}/status")
    public ApiResponse<FaultRecord> updateFaultStatus(@PathVariable String faultId, 
                                                      @RequestParam String status) {
        FaultRecord fault = faultService.updateFaultStatus(faultId, status);
        return ApiResponse.success(fault);
    }

    @GetMapping("/count")
    public ApiResponse<Map<String, Long>> getFaultCount() {
        Map<String, Long> count = new HashMap<>();
        count.put("total", faultService.count());
        count.put("pending", faultService.countByStatus("pending"));
        count.put("processing", faultService.countByStatus("processing"));
        count.put("resolved", faultService.countByStatus("resolved"));
        return ApiResponse.success(count);
    }
}
