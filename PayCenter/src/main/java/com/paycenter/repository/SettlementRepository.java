package com.paycenter.repository;

import com.paycenter.entity.Settlement;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;

@Repository
public interface SettlementRepository extends JpaRepository<Settlement, String> {
    List<Settlement> findByMerchantId(String merchantId);
    List<Settlement> findByMerchantIdAndSettlementPeriodBetween(String merchantId, LocalDate start, LocalDate end);
}
