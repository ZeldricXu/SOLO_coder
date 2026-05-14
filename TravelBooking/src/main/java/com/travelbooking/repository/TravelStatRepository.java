package com.travelbooking.repository;

import com.travelbooking.model.TravelStat;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface TravelStatRepository extends JpaRepository<TravelStat, String> {
    Optional<TravelStat> findByStatMonth(String statMonth);
}
