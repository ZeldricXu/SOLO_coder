package com.medical.appointment.controller;

import com.medical.appointment.dto.ApiResponse;
import com.medical.appointment.entity.Department;
import com.medical.appointment.service.DepartmentService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/departments")
public class DepartmentController {
    
    private final DepartmentService departmentService;
    
    public DepartmentController(DepartmentService departmentService) {
        this.departmentService = departmentService;
    }
    
    @PostMapping
    public ResponseEntity<ApiResponse<Department>> createDepartment(@RequestBody Department department) {
        try {
            Department created = departmentService.createDepartment(department);
            return ResponseEntity.ok(ApiResponse.success("科室创建成功", created));
        } catch (Exception e) {
            return ResponseEntity.badRequest()
                    .body(ApiResponse.badRequest("创建科室失败: " + e.getMessage()));
        }
    }
    
    @GetMapping
    public ResponseEntity<ApiResponse<List<Department>>> getAllDepartments() {
        List<Department> departments = departmentService.getAllDepartments();
        return ResponseEntity.ok(ApiResponse.success(departments));
    }
    
    @GetMapping("/hospital/{hospitalId}")
    public ResponseEntity<ApiResponse<List<Department>>> getDepartmentsByHospital(
            @PathVariable String hospitalId) {
        List<Department> departments = departmentService.getDepartmentsByHospital(hospitalId);
        return ResponseEntity.ok(ApiResponse.success(departments));
    }
    
    @GetMapping("/hospital/{hospitalId}/active")
    public ResponseEntity<ApiResponse<List<Department>>> getActiveDepartmentsByHospital(
            @PathVariable String hospitalId) {
        List<Department> departments = departmentService.getActiveDepartmentsByHospital(hospitalId);
        return ResponseEntity.ok(ApiResponse.success(departments));
    }
    
    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<Department>> getDepartmentById(@PathVariable String id) {
        return departmentService.getDepartmentById(id)
                .map(department -> ResponseEntity.ok(ApiResponse.success(department)))
                .orElse(ResponseEntity.status(404)
                        .body(ApiResponse.notFound("科室不存在")));
    }
    
    @PutMapping("/{id}")
    public ResponseEntity<ApiResponse<Department>> updateDepartment(
            @PathVariable String id, @RequestBody Department department) {
        try {
            Department updated = departmentService.updateDepartment(id, department);
            return ResponseEntity.ok(ApiResponse.success("科室更新成功", updated));
        } catch (Exception e) {
            return ResponseEntity.status(404)
                    .body(ApiResponse.notFound("更新科室失败: " + e.getMessage()));
        }
    }
    
    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<Void>> deleteDepartment(@PathVariable String id) {
        departmentService.deleteDepartment(id);
        return ResponseEntity.ok(ApiResponse.success("科室删除成功", null));
    }
}
