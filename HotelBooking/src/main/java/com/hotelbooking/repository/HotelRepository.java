package com.hotelbooking.repository;

import com.hotelbooking.model.Hotel;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface HotelRepository extends JpaRepository<Hotel, String> {
    List<Hotel> findByHotelStatus(String status);
    List<Hotel> findByHotelType(String type);
}
