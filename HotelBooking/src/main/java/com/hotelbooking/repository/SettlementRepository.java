package com.hotelbooking.repository;

import com.hotelbooking.model.Settlement;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface SettlementRepository extends JpaRepository<Settlement, String> {
    Optional<Settlement> findByBookingId(String bookingId);

    @Query("SELECT COALESCE(SUM(s.totalAmount), 0) FROM Settlement s WHERE EXISTS " +
           "(SELECT b FROM Booking b WHERE b.bookingId = s.bookingId AND b.hotelId = :hotelId) " +
           "AND FUNCTION('DATE_FORMAT', s.settlementTime, '%Y-%m') = :month")
    Double sumTotalAmountByHotelIdAndMonth(@Param("hotelId") String hotelId, @Param("month") String month);
}
