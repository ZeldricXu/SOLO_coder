package com.eventticket.repository;

import com.eventticket.entity.Statistics;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface StatisticsRepository extends JpaRepository<Statistics, String> {
    Optional<Statistics> findByStatMonth(String statMonth);
}
