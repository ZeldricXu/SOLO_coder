package com.homeservice.service;

import com.homeservice.dto.CustomerRequest;
import com.homeservice.entity.Customer;
import com.homeservice.enums.CustomerStatus;
import com.homeservice.exception.BusinessException;
import com.homeservice.exception.ResourceNotFoundException;
import com.homeservice.repository.CustomerRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

@Service
public class CustomerService {

    @Autowired
    private CustomerRepository customerRepository;

    private final AtomicLong customerCounter = new AtomicLong(0);

    public Customer createCustomer(CustomerRequest request) {
        String customerId = "customer_" + String.format("%03d", customerCounter.incrementAndGet());
        Customer customer = new Customer(
            customerId,
            request.getCustomerName(),
            request.getCustomerPhone(),
            request.getCustomerAddress(),
            request.getCustomerRegion()
        );
        return customerRepository.save(customer);
    }

    public List<Customer> getAllCustomers() {
        return customerRepository.findAll();
    }

    public Customer getCustomerById(String customerId) {
        return customerRepository.findByCustomerId(customerId)
            .orElseThrow(() -> new ResourceNotFoundException("Customer not found: " + customerId));
    }

    public Customer updateCustomer(String customerId, CustomerRequest request) {
        Customer customer = getCustomerById(customerId);
        customer.setCustomerName(request.getCustomerName());
        customer.setCustomerPhone(request.getCustomerPhone());
        customer.setCustomerAddress(request.getCustomerAddress());
        customer.setCustomerRegion(request.getCustomerRegion());
        return customerRepository.save(customer);
    }

    public void deleteCustomer(String customerId) {
        Customer customer = getCustomerById(customerId);
        customerRepository.delete(customer);
    }

    public Customer freezeCustomer(String customerId) {
        Customer customer = getCustomerById(customerId);
        customer.setCustomerStatus(CustomerStatus.FROZEN);
        return customerRepository.save(customer);
    }

    public Customer activateCustomer(String customerId) {
        Customer customer = getCustomerById(customerId);
        customer.setCustomerStatus(CustomerStatus.ACTIVE);
        return customerRepository.save(customer);
    }

    public void incrementBookingCount(String customerId) {
        Customer customer = getCustomerById(customerId);
        customer.setTotalBookings(customer.getTotalBookings() + 1);
        customerRepository.save(customer);
    }

    public void validateCustomerStatus(String customerId) {
        Customer customer = getCustomerById(customerId);
        if (customer.getCustomerStatus() == CustomerStatus.FROZEN) {
            throw new BusinessException("Customer is frozen");
        }
    }
}
