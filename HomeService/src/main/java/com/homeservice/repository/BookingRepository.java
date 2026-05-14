package com.homeservice.repository;

import com.homeservice.entity.Booking;
import com.homeservice.enums.BookingStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public interface BookingRepository extends JpaRepository<Booking, Long> {
    Optional<Booking> findByBookingId(String bookingId);
    List<Booking> findByStaffId(String staffId);
    List<Booking> findByCustomerId(String customerId);
    List<Booking> findByBookingStatus(BookingStatus status);
    boolean existsByBookingId(String bookingId);
    @Query("SELECT COUNT(b) FROM Booking b WHERE b.bookingStatus = :status")
    Long countByStatus(@Param("status") BookingStatus status);
    @Query("SELECT SUM(b.bookingAmount) FROM Booking b WHERE b.bookingStatus = :status")
    Double sumAmountByStatus(@Param("status") BookingStatus status);
    @Query("SELECT b FROM Booking b WHERE b.staffId = :staffId AND b.bookingStatus = :status AND b.serviceTime BETWEEN :startTime AND :endTime")
    List<Booking> findConflictingBookings(@Param("staffId") String staffId, 
                                           @Param("status") BookingStatus status,
                                           @Param("startTime") Instant startTime, 
                                           @Param("endTime") Instant endTime);
}
