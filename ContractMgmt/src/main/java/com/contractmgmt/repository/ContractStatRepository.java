package com.contractmgmt.repository;

import com.contractmgmt.entity.ContractStat;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ContractStatRepository extends JpaRepository<ContractStat, String> {

    Optional<ContractStat> findByStatId(String statId);

    Optional<ContractStat> findByStatMonth(String statMonth);

    @Query("SELECT s FROM ContractStat s WHERE s.statMonth BETWEEN :startMonth AND :endMonth ORDER BY s.statMonth")
    List<ContractStat> findByStatMonthBetween(
            @Param("startMonth") String startMonth,
            @Param("endMonth") String endMonth);

    @Query("SELECT s FROM ContractStat s ORDER BY s.statMonth DESC")
    List<ContractStat> findAllOrderByStatMonthDesc();
}
