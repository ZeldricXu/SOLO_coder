package com.fooddelivery.repository;

import com.fooddelivery.entity.Stat;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.Optional;
import java.util.List;

@Repository
public interface StatRepository extends JpaRepository<Stat, String> {
    Optional<Stat> findByStatId(String statId);
    Optional<Stat> findByStatMonth(String statMonth);
    List<Stat> findAllByOrderByStatMonthDesc();
    boolean existsByStatMonth(String statMonth);
}
