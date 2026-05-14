package com.travelbooking.repository;

import com.travelbooking.model.Settlement;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface SettlementRepository extends JpaRepository<Settlement, String> {
    List<Settlement> findByBookingId(String bookingId);
    List<Settlement> findByTouristId(String touristId);
    Optional<Settlement> findByBookingIdAndPaymentStatus(String bookingId, String paymentStatus);
}
