package com.homeservice.repository;

import com.homeservice.entity.ServiceStat;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.Optional;

@Repository
public interface ServiceStatRepository extends JpaRepository<ServiceStat, Long> {
    Optional<ServiceStat> findByStatMonth(String statMonth);
    Optional<ServiceStat> findByStatId(String statId);
}
