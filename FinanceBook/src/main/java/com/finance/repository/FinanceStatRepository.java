package com.finance.repository;

import com.finance.entity.FinanceStat;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface FinanceStatRepository extends JpaRepository<FinanceStat, String> {
    List<FinanceStat> findByAccountIdOrderByStatMonthDesc(String accountId);
    Optional<FinanceStat> findByAccountIdAndStatMonth(String accountId, String statMonth);
}
