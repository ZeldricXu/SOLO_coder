package com.cms.repository;

import com.cms.entity.MonthlyStatistics;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface MonthlyStatisticsRepository extends JpaRepository<MonthlyStatistics, String> {

    Optional<MonthlyStatistics> findByStatMonth(String statMonth);
}
