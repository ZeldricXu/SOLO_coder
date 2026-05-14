package com.eventticket.repository;

import com.eventticket.entity.Verification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface VerificationRepository extends JpaRepository<Verification, String> {
    List<Verification> findByTicketId(String ticketId);
    List<Verification> findByVerifyResult(String verifyResult);
    
    @Query("SELECT COUNT(v) FROM Verification v WHERE v.verifyTime BETWEEN :start AND :end AND v.verifyResult = 'valid'")
    long countValidVerificationsByDateRange(@Param("start") LocalDateTime start, @Param("end") LocalDateTime end);
    
    @Query("SELECT v FROM Verification v WHERE v.ticketId = :ticketId ORDER BY v.verifyTime DESC")
    List<Verification> findLatestVerificationsByTicketId(@Param("ticketId") String ticketId);
}
