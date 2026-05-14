package com.hotelbooking.controller;

import com.hotelbooking.dto.ApiResponse;
import com.hotelbooking.model.ServiceRecord;
import com.hotelbooking.service.GuestService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/services")
public class ServiceController {

    private final GuestService guestService;

    public ServiceController(GuestService guestService) {
        this.guestService = guestService;
    }

    @PostMapping("/request")
    public ResponseEntity<ApiResponse<ServiceRecord>> createServiceRequest(
            @RequestParam String roomId,
            @RequestParam String serviceType,
            @RequestParam(required = false) String serviceRequest,
            @RequestParam(required = false) Double serviceCharge) {
        try {
            ServiceRecord record = guestService.createServiceRequest(roomId, serviceType, serviceRequest, serviceCharge);
            return ResponseEntity.ok(ApiResponse.success("服务请求创建成功", record));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(ApiResponse.error(400, e.getMessage()));
        }
    }

    @PostMapping("/{serviceId}/process")
    public ResponseEntity<ApiResponse<ServiceRecord>> processService(@PathVariable String serviceId) {
        try {
            ServiceRecord record = guestService.processService(serviceId);
            return ResponseEntity.ok(ApiResponse.success("服务开始处理", record));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(ApiResponse.error(400, e.getMessage()));
        }
    }

    @PostMapping("/{serviceId}/complete")
    public ResponseEntity<ApiResponse<ServiceRecord>> completeService(@PathVariable String serviceId) {
        try {
            ServiceRecord record = guestService.completeService(serviceId);
            return ResponseEntity.ok(ApiResponse.success("服务完成", record));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(ApiResponse.error(400, e.getMessage()));
        }
    }

    @PostMapping("/{serviceId}/cancel")
    public ResponseEntity<ApiResponse<ServiceRecord>> cancelService(@PathVariable String serviceId) {
        try {
            ServiceRecord record = guestService.cancelService(serviceId);
            return ResponseEntity.ok(ApiResponse.success("服务取消成功", record));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(ApiResponse.error(400, e.getMessage()));
        }
    }

    @GetMapping("/room/{roomId}")
    public ResponseEntity<ApiResponse<List<ServiceRecord>>> getServicesByRoom(@PathVariable String roomId) {
        List<ServiceRecord> records = guestService.getServicesByRoom(roomId);
        return ResponseEntity.ok(ApiResponse.success(records));
    }

    @GetMapping("/status/{status}")
    public ResponseEntity<ApiResponse<List<ServiceRecord>>> getServicesByStatus(@PathVariable String status) {
        List<ServiceRecord> records = guestService.getServicesByStatus(status);
        return ResponseEntity.ok(ApiResponse.success(records));
    }
}
