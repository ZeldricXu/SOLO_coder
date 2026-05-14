package com.servicedesk.repository;

import com.servicedesk.entity.Statistic;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.Optional;

@Repository
public interface StatisticRepository extends JpaRepository<Statistic, String> {
    Optional<Statistic> findByStatDate(LocalDate statDate);
    boolean existsByStatDate(LocalDate statDate);
}
