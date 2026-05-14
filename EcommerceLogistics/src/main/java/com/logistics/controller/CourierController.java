package com.logistics.controller;

import com.logistics.dto.ApiResponse;
import com.logistics.entity.Courier;
import com.logistics.service.CourierService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Optional;

@RestController
@RequestMapping("/api/v1/couriers")
@RequiredArgsConstructor
public class CourierController {

    private final CourierService courierService;

    @PostMapping("/create")
    public ApiResponse<Courier> createCourier(@RequestBody Courier courier) {
        Courier created = courierService.createCourier(courier);
        return ApiResponse.success(created);
    }

    @GetMapping("/{courierId}")
    public ApiResponse<Courier> getCourierById(@PathVariable String courierId) {
        Courier courier = courierService.getCourierById(courierId);
        return ApiResponse.success(courier);
    }

    @GetMapping("/list")
    public ApiResponse<List<Courier>> getAllCouriers() {
        List<Courier> couriers = courierService.getAllCouriers();
        return ApiResponse.success(couriers);
    }

    @GetMapping("/station/{stationId}")
    public ApiResponse<List<Courier>> getCouriersByStation(@PathVariable String stationId) {
        List<Courier> couriers = courierService.getCouriersByStation(stationId);
        return ApiResponse.success(couriers);
    }

    @GetMapping("/available")
    public ApiResponse<List<Courier>> getAvailableCouriers() {
        List<Courier> couriers = courierService.getAvailableCouriers();
        return ApiResponse.success(couriers);
    }

    @GetMapping("/best/{stationId}")
    public ApiResponse<Optional<Courier>> getBestCourier(@PathVariable String stationId) {
        Optional<Courier> courier = courierService.selectBestCourier(stationId);
        return ApiResponse.success(courier);
    }

    @PutMapping("/{courierId}")
    public ApiResponse<Courier> updateCourier(@PathVariable String courierId, @RequestBody Courier courierDetails) {
        Courier updated = courierService.updateCourier(courierId, courierDetails);
        return ApiResponse.success(updated);
    }

    @DeleteMapping("/{courierId}")
    public ApiResponse<Void> deleteCourier(@PathVariable String courierId) {
        courierService.deleteCourier(courierId);
        return ApiResponse.success(null);
    }
}
