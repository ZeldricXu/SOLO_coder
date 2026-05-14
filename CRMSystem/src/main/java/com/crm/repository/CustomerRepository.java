package com.crm.repository;

import com.crm.entity.Customer;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface CustomerRepository extends JpaRepository<Customer, String> {
    Optional<Customer> findByCustomerId(String customerId);
    List<Customer> findByCustomerStatus(String customerStatus);
    List<Customer> findByCustomerType(String customerType);
    
    @Query("SELECT COUNT(c) FROM Customer c WHERE MONTH(c.createdAt) = MONTH(CURRENT_DATE) AND YEAR(c.createdAt) = YEAR(CURRENT_DATE)")
    Long countCurrentMonthCustomers();
    
    @Query("SELECT COUNT(c) FROM Customer c WHERE c.customerStatus = 'deal'")
    Long countDealCustomers();
}
