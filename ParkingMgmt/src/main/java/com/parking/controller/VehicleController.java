package com.parking.controller;

import com.parking.dto.ApiResponse;
import com.parking.entity.Vehicle;
import com.parking.service.VehicleService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/vehicles")
public class VehicleController {

    @Autowired
    private VehicleService vehicleService;

    @PostMapping("/create")
    public ApiResponse<Vehicle> createVehicle(@RequestBody Map<String, String> request) {
        String vehicleNumber = request.get("vehicleNumber");
        String vehicleType = request.get("vehicleType");
        String vehicleOwner = request.get("vehicleOwner");
        String vehiclePhone = request.get("vehiclePhone");

        if (vehicleNumber == null) {
            return ApiResponse.error(400, "车牌号码不能为空");
        }

        Vehicle vehicle = vehicleService.createOrGetVehicle(vehicleNumber, vehicleType, vehicleOwner, vehiclePhone);
        return ApiResponse.success(vehicle);
    }

    @GetMapping("/{vehicleId}")
    public ApiResponse<Vehicle> getVehicle(@PathVariable String vehicleId) {
        Vehicle vehicle = vehicleService.getVehicleById(vehicleId);
        return ApiResponse.success(vehicle);
    }

    @GetMapping("/number/{vehicleNumber}")
    public ApiResponse<Vehicle> getVehicleByNumber(@PathVariable String vehicleNumber) {
        Vehicle vehicle = vehicleService.getVehicleByNumber(vehicleNumber);
        if (vehicle == null) {
            return ApiResponse.error(404, "车辆不存在");
        }
        return ApiResponse.success(vehicle);
    }

    @GetMapping("/list")
    public ApiResponse<List<Vehicle>> listVehicles() {
        List<Vehicle> vehicles = vehicleService.getAllVehicles();
        return ApiResponse.success(vehicles);
    }

    @PutMapping("/{vehicleId}/status")
    public ApiResponse<Vehicle> updateVehicleStatus(@PathVariable String vehicleId, @RequestBody Map<String, String> request) {
        String status = request.get("status");
        if (status == null) {
            return ApiResponse.error(400, "状态不能为空");
        }
        Vehicle vehicle = vehicleService.updateVehicleStatus(vehicleId, status);
        return ApiResponse.success(vehicle);
    }

    @DeleteMapping("/{vehicleId}")
    public ApiResponse<Void> deleteVehicle(@PathVariable String vehicleId) {
        vehicleService.deleteVehicle(vehicleId);
        return ApiResponse.success(null);
    }
}
