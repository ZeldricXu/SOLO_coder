package com.logistics.repository;

import com.logistics.entity.Courier;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public interface CourierRepository extends JpaRepository<Courier, String> {

    List<Courier> findByCourierStation(String stationId);

    List<Courier> findByCourierStatus(String status);

    List<Courier> findByCourierStationAndCourierStatus(String stationId, String status);
}
