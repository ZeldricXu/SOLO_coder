package com.schedulebook.repository;

import com.schedulebook.model.BookingHistory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface BookingHistoryRepository extends JpaRepository<BookingHistory, Long> {
    
    Optional<BookingHistory> findByHistoryId(String historyId);
    
    List<BookingHistory> findByBookingId(String bookingId);
    
    List<BookingHistory> findByUserId(String userId);
    
    @Query("SELECT bh FROM BookingHistory bh WHERE bh.userId = :userId ORDER BY bh.actionTime DESC")
    List<BookingHistory> findByUserIdOrderByActionTimeDesc(@Param("userId") String userId);
    
    @Query("SELECT bh FROM BookingHistory bh WHERE bh.bookingDate = :bookingDate ORDER BY bh.actionTime DESC")
    List<BookingHistory> findByBookingDate(@Param("bookingDate") LocalDate bookingDate);
    
    @Query("SELECT bh FROM BookingHistory bh WHERE bh.bookingDate BETWEEN :startDate AND :endDate ORDER BY bh.actionTime DESC")
    List<BookingHistory> findByDateRange(
            @Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate
    );
    
    @Query("SELECT bh FROM BookingHistory bh WHERE bh.userId = :userId AND bh.bookingDate BETWEEN :startDate AND :endDate ORDER BY bh.actionTime DESC")
    List<BookingHistory> findByUserIdAndDateRange(
            @Param("userId") String userId,
            @Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate
    );
}
