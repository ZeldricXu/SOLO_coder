package com.fitnesscenter.repository;

import com.fitnesscenter.model.Statistic;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface StatisticRepository extends JpaRepository<Statistic, String> {
    
    Optional<Statistic> findByStatId(String statId);
    
    Optional<Statistic> findByStatMonth(String statMonth);
    
    boolean existsByStatMonth(String statMonth);
}
