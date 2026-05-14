package com.logistics.controller;

import com.logistics.dto.ApiResponse;
import com.logistics.dto.CreateLogisticsRequest;
import com.logistics.dto.CreateLogisticsResponse;
import com.logistics.entity.Logistics;
import com.logistics.service.LogisticsService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/logistics")
@RequiredArgsConstructor
public class LogisticsController {

    private final LogisticsService logisticsService;

    @PostMapping("/create")
    public ApiResponse<CreateLogisticsResponse> createLogistics(@Valid @RequestBody CreateLogisticsRequest request) {
        CreateLogisticsResponse response = logisticsService.createLogistics(request);
        return ApiResponse.success(response);
    }

    @GetMapping("/{logisticsId}")
    public ApiResponse<Logistics> getLogisticsById(@PathVariable String logisticsId) {
        Logistics logistics = logisticsService.getLogisticsById(logisticsId);
        return ApiResponse.success(logistics);
    }

    @GetMapping("/number/{logisticsNumber}")
    public ApiResponse<Logistics> getLogisticsByNumber(@PathVariable String logisticsNumber) {
        Logistics logistics = logisticsService.getLogisticsByNumber(logisticsNumber);
        return ApiResponse.success(logistics);
    }

    @GetMapping("/order/{orderId}")
    public ApiResponse<Logistics> getLogisticsByOrderId(@PathVariable String orderId) {
        Logistics logistics = logisticsService.getLogisticsByOrderId(orderId);
        return ApiResponse.success(logistics);
    }

    @GetMapping("/list")
    public ApiResponse<List<Logistics>> getAllLogistics() {
        List<Logistics> logisticsList = logisticsService.getAllLogistics();
        return ApiResponse.success(logisticsList);
    }
}
