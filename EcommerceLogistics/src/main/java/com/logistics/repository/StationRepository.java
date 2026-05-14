package com.logistics.repository;

import com.logistics.entity.Station;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public interface StationRepository extends JpaRepository<Station, String> {

    List<Station> findByStationStatus(String status);

    List<Station> findByStationRegion(String region);
}
