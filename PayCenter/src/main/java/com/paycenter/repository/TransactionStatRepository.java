package com.paycenter.repository;

import com.paycenter.entity.TransactionStat;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface TransactionStatRepository extends JpaRepository<TransactionStat, String> {
    Optional<TransactionStat> findByMerchantIdAndStatDate(String merchantId, LocalDate statDate);
    List<TransactionStat> findByMerchantIdAndStatDateBetween(String merchantId, LocalDate start, LocalDate end);
}
