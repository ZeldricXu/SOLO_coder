package com.paycenter.repository;

import com.paycenter.entity.SettlementPeriod;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface SettlementPeriodRepository extends JpaRepository<SettlementPeriod, String> {
    List<SettlementPeriod> findByEnabledTrue();
    Optional<SettlementPeriod> findByPeriodIdAndEnabledTrue(String periodId);
}
