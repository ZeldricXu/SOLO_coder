package com.finance.repository;

import com.finance.entity.TransactionType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface TransactionTypeRepository extends JpaRepository<TransactionType, String> {
    Optional<TransactionType> findByTypeCode(String typeCode);
    List<TransactionType> findByTypeStatus(String typeStatus);
    List<TransactionType> findByTypeDirection(String typeDirection);
    Optional<TransactionType> findByTypeCodeAndTypeStatus(String typeCode, String status);
    boolean existsByTypeCode(String typeCode);
}
