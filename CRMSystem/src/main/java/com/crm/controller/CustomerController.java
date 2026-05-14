package com.crm.controller;

import com.crm.common.ApiResponse;
import com.crm.dto.CustomerRequest;
import com.crm.entity.Customer;
import com.crm.service.CustomerService;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/customers")
public class CustomerController {

    @Autowired
    private CustomerService customerService;

    @PostMapping("/create")
    public ApiResponse<Map<String, Object>> createCustomer(@Valid @RequestBody CustomerRequest request) {
        Map<String, Object> result = customerService.createCustomer(request);
        return ApiResponse.success(result);
    }

    @GetMapping("/{customerId}")
    public ApiResponse<Customer> getCustomerById(@PathVariable String customerId) {
        Customer customer = customerService.getCustomerById(customerId);
        return ApiResponse.success(customer);
    }

    @GetMapping
    public ApiResponse<List<Customer>> getAllCustomers() {
        List<Customer> customers = customerService.getAllCustomers();
        return ApiResponse.success(customers);
    }

    @GetMapping("/status/{status}")
    public ApiResponse<List<Customer>> getCustomersByStatus(@PathVariable String status) {
        List<Customer> customers = customerService.getCustomersByStatus(status);
        return ApiResponse.success(customers);
    }

    @PutMapping("/{customerId}")
    public ApiResponse<Customer> updateCustomer(
            @PathVariable String customerId,
            @RequestBody CustomerRequest request) {
        Customer customer = customerService.updateCustomer(customerId, request);
        return ApiResponse.success(customer);
    }

    @PutMapping("/{customerId}/status/{newStatus}")
    public ApiResponse<Customer> updateCustomerStatus(
            @PathVariable String customerId,
            @PathVariable String newStatus) {
        Customer customer = customerService.updateCustomerStatus(customerId, newStatus);
        return ApiResponse.success(customer);
    }
}
