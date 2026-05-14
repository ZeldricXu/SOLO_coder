package com.restaurant.mgmt.repository;

import com.restaurant.mgmt.model.SalesStat;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface SalesStatRepository extends JpaRepository<SalesStat, String> {
    Optional<SalesStat> findByStatDate(LocalDate statDate);
    List<SalesStat> findByStatDateBetween(LocalDate startDate, LocalDate endDate);
}
