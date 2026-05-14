package com.schedulebook.repository;

import com.schedulebook.model.Booking;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface BookingRepository extends JpaRepository<Booking, Long> {
    
    Optional<Booking> findByBookingId(String bookingId);
    
    List<Booking> findByUserId(String userId);
    
    List<Booking> findByResourceIdAndBookingDate(String resourceId, LocalDate bookingDate);
    
    List<Booking> findByResourceIdAndBookingDateAndBookingTime(String resourceId, LocalDate bookingDate, LocalTime bookingTime);
    
    List<Booking> findByBookingStatus(String bookingStatus);
    
    @Query("SELECT b FROM Booking b WHERE b.resourceType = :resourceType AND b.bookingDate = :bookingDate AND b.bookingTime = :bookingTime")
    List<Booking> findConflictingBookings(
            @Param("resourceType") String resourceType,
            @Param("bookingDate") LocalDate bookingDate,
            @Param("bookingTime") LocalTime bookingTime
    );
    
    @Query("SELECT COUNT(b) FROM Booking b WHERE b.bookingDate = :date")
    Long countByDate(@Param("date") LocalDate date);
    
    @Query("SELECT COUNT(b) FROM Booking b WHERE b.bookingDate = :date AND b.bookingStatus = :status")
    Long countByDateAndStatus(@Param("date") LocalDate date, @Param("status") String status);
    
    @Query("SELECT COUNT(b) FROM Booking b WHERE b.bookingDate BETWEEN :startDate AND :endDate")
    Long countByDateRange(@Param("startDate") LocalDate startDate, @Param("endDate") LocalDate endDate);
    
    boolean existsByBookingId(String bookingId);
}
