package com.crm.repository;

import com.crm.entity.CustomerTag;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface CustomerTagRepository extends JpaRepository<CustomerTag, Long> {
    List<CustomerTag> findByCustomerId(String customerId);
    List<CustomerTag> findByTagId(String tagId);
    void deleteByCustomerIdAndTagId(String customerId, String tagId);
}
