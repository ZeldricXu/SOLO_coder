package com.recruitment.repository;

import com.recruitment.model.Statistics;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface StatisticsRepository extends JpaRepository<Statistics, String> {
    Optional<Statistics> findByStatMonth(String statMonth);
    Optional<Statistics> findByStatId(String statId);
    boolean existsByStatMonth(String statMonth);
}
