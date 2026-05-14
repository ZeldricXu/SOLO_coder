package com.homeservice.controller;

import com.homeservice.dto.ApiResponse;
import com.homeservice.dto.StaffRequest;
import com.homeservice.entity.Staff;
import com.homeservice.enums.StaffStatus;
import com.homeservice.service.StaffService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/api/v1/staff")
public class StaffController {

    @Autowired
    private StaffService staffService;

    @PostMapping
    public ApiResponse<Staff> createStaff(@RequestBody StaffRequest request) {
        Staff created = staffService.createStaff(request);
        return ApiResponse.success(created);
    }

    @GetMapping
    public ApiResponse<List<Staff>> getAllStaff() {
        List<Staff> staffList = staffService.getAllStaff();
        return ApiResponse.success(staffList);
    }

    @GetMapping("/available")
    public ApiResponse<List<Staff>> getAvailableStaff() {
        List<Staff> staffList = staffService.getAvailableStaff();
        return ApiResponse.success(staffList);
    }

    @GetMapping("/type/{type}")
    public ApiResponse<List<Staff>> getStaffByType(@PathVariable String type) {
        List<Staff> staffList = staffService.getStaffByType(type);
        return ApiResponse.success(staffList);
    }

    @GetMapping("/region/{region}")
    public ApiResponse<List<Staff>> getStaffByRegion(@PathVariable String region) {
        List<Staff> staffList = staffService.getStaffByRegion(region);
        return ApiResponse.success(staffList);
    }

    @GetMapping("/{staffId}")
    public ApiResponse<Staff> getStaff(@PathVariable String staffId) {
        Staff staff = staffService.getStaffById(staffId);
        return ApiResponse.success(staff);
    }

    @PutMapping("/{staffId}")
    public ApiResponse<Staff> updateStaff(@PathVariable String staffId, @RequestBody StaffRequest request) {
        Staff updated = staffService.updateStaff(staffId, request);
        return ApiResponse.success(updated);
    }

    @DeleteMapping("/{staffId}")
    public ApiResponse<Void> deleteStaff(@PathVariable String staffId) {
        staffService.deleteStaff(staffId);
        return ApiResponse.success(null);
    }

    @PostMapping("/{staffId}/status/{status}")
    public ApiResponse<Staff> updateStaffStatus(@PathVariable String staffId, @PathVariable StaffStatus status) {
        Staff updated = staffService.updateStaffStatus(staffId, status);
        return ApiResponse.success(updated);
    }
}
