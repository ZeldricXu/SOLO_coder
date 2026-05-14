package com.homeservice.repository;

import com.homeservice.entity.Customer;
import com.homeservice.enums.CustomerStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;

@Repository
public interface CustomerRepository extends JpaRepository<Customer, Long> {
    Optional<Customer> findByCustomerId(String customerId);
    List<Customer> findByCustomerStatus(CustomerStatus status);
    boolean existsByCustomerId(String customerId);
    @Query("SELECT COUNT(c) FROM Customer c")
    Long countTotalCustomers();
}
