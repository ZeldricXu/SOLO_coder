package com.restaurant.mgmt.controller;

import com.restaurant.mgmt.dto.ApiResponse;
import com.restaurant.mgmt.model.Employee;
import com.restaurant.mgmt.service.EmployeeService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/employees")
public class EmployeeController {

    @Autowired
    private EmployeeService employeeService;

    @PostMapping
    public ApiResponse<Employee> createEmployee(@RequestBody Employee employee) {
        Employee saved = employeeService.createEmployee(employee);
        return ApiResponse.success(saved);
    }

    @GetMapping
    public ApiResponse<List<Employee>> getAllEmployees() {
        List<Employee> employees = employeeService.getAllEmployees();
        return ApiResponse.success(employees);
    }

    @GetMapping("/{employeeId}")
    public ApiResponse<Employee> getEmployee(@PathVariable String employeeId) {
        Employee employee = employeeService.getEmployeeById(employeeId);
        return ApiResponse.success(employee);
    }

    @GetMapping("/active")
    public ApiResponse<List<Employee>> getActiveEmployees() {
        List<Employee> employees = employeeService.getActiveEmployees();
        return ApiResponse.success(employees);
    }

    @GetMapping("/department/{department}")
    public ApiResponse<List<Employee>> getEmployeesByDepartment(@PathVariable String department) {
        List<Employee> employees = employeeService.getEmployeesByDepartment(department);
        return ApiResponse.success(employees);
    }

    @GetMapping("/position/{position}")
    public ApiResponse<List<Employee>> getEmployeesByPosition(@PathVariable String position) {
        List<Employee> employees = employeeService.getEmployeesByPosition(position);
        return ApiResponse.success(employees);
    }

    @PutMapping("/{employeeId}")
    public ApiResponse<Employee> updateEmployee(
            @PathVariable String employeeId,
            @RequestBody Employee employee) {
        Employee updated = employeeService.updateEmployee(employeeId, employee);
        return ApiResponse.success(updated);
    }

    @DeleteMapping("/{employeeId}")
    public ApiResponse<Void> deleteEmployee(@PathVariable String employeeId) {
        employeeService.deleteEmployee(employeeId);
        return ApiResponse.success(null);
    }

    @PostMapping("/{employeeId}/activate")
    public ApiResponse<Employee> activateEmployee(@PathVariable String employeeId) {
        Employee employee = employeeService.activateEmployee(employeeId);
        return ApiResponse.success(employee);
    }

    @PostMapping("/{employeeId}/deactivate")
    public ApiResponse<Employee> deactivateEmployee(@PathVariable String employeeId) {
        Employee employee = employeeService.deactivateEmployee(employeeId);
        return ApiResponse.success(employee);
    }
}
