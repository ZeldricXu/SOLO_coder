package com.homeservice.controller;

import com.homeservice.dto.ApiResponse;
import com.homeservice.dto.CustomerRequest;
import com.homeservice.entity.Customer;
import com.homeservice.service.CustomerService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/api/v1/customers")
public class CustomerController {

    @Autowired
    private CustomerService customerService;

    @PostMapping
    public ApiResponse<Customer> createCustomer(@RequestBody CustomerRequest request) {
        Customer created = customerService.createCustomer(request);
        return ApiResponse.success(created);
    }

    @GetMapping
    public ApiResponse<List<Customer>> getAllCustomers() {
        List<Customer> customers = customerService.getAllCustomers();
        return ApiResponse.success(customers);
    }

    @GetMapping("/{customerId}")
    public ApiResponse<Customer> getCustomer(@PathVariable String customerId) {
        Customer customer = customerService.getCustomerById(customerId);
        return ApiResponse.success(customer);
    }

    @PutMapping("/{customerId}")
    public ApiResponse<Customer> updateCustomer(@PathVariable String customerId, @RequestBody CustomerRequest request) {
        Customer updated = customerService.updateCustomer(customerId, request);
        return ApiResponse.success(updated);
    }

    @DeleteMapping("/{customerId}")
    public ApiResponse<Void> deleteCustomer(@PathVariable String customerId) {
        customerService.deleteCustomer(customerId);
        return ApiResponse.success(null);
    }

    @PostMapping("/{customerId}/freeze")
    public ApiResponse<Customer> freezeCustomer(@PathVariable String customerId) {
        Customer frozen = customerService.freezeCustomer(customerId);
        return ApiResponse.success(frozen);
    }

    @PostMapping("/{customerId}/activate")
    public ApiResponse<Customer> activateCustomer(@PathVariable String customerId) {
        Customer activated = customerService.activateCustomer(customerId);
        return ApiResponse.success(activated);
    }
}
