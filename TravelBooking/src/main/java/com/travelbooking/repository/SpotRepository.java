package com.travelbooking.repository;

import com.travelbooking.model.Spot;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface SpotRepository extends JpaRepository<Spot, String> {
    List<Spot> findBySpotStatus(String spotStatus);
}
