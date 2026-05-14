package com.fitnesscenter.repository;

import com.fitnesscenter.model.Booking;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public interface BookingRepository extends JpaRepository<Booking, String> {
    
    Optional<Booking> findByBookingId(String bookingId);
    
    List<Booking> findByMemberId(String memberId);
    
    List<Booking> findByCourseId(String courseId);
    
    List<Booking> findByCoachId(String coachId);
    
    List<Booking> findByBookingStatus(String bookingStatus);
    
    List<Booking> findByMemberIdAndBookingStatus(String memberId, String bookingStatus);
    
    Optional<Booking> findByMemberIdAndCourseId(String memberId, String courseId);
    
    List<Booking> findByBookingTimeBetween(Instant startTime, Instant endTime);
    
    boolean existsByMemberIdAndCourseId(String memberId, String courseId);
    
    boolean existsByBookingId(String bookingId);
}
