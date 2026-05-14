package com.hotelbooking.repository;

import com.hotelbooking.model.HotelStat;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface HotelStatRepository extends JpaRepository<HotelStat, String> {
    Optional<HotelStat> findByHotelIdAndStatMonth(String hotelId, String statMonth);
    List<HotelStat> findByHotelId(String hotelId);
    List<HotelStat> findByStatMonth(String statMonth);
}
