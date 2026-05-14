package com.memberscore.repository;

import com.memberscore.entity.PointRecord;
import com.memberscore.enums.PointType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface PointRecordRepository extends JpaRepository<PointRecord, Long> {
    
    Optional<PointRecord> findByPointId(String pointId);
    
    List<PointRecord> findByMemberIdOrderByCreatedAtDesc(String memberId);
    
    List<PointRecord> findByMemberIdAndPointTypeOrderByCreatedAtDesc(String memberId, PointType pointType);
    
    List<PointRecord> findByMemberIdAndCreatedAtBetweenOrderByCreatedAtDesc(
            String memberId, LocalDateTime start, LocalDateTime end);
    
    @Query("SELECT r FROM PointRecord r WHERE r.expireAt <= :today AND r.isExpired = false AND r.pointType = 'EARN'")
    List<PointRecord> findExpiredPoints(@Param("today") LocalDate today);
    
    @Query("SELECT COALESCE(SUM(r.pointAmount), 0) FROM PointRecord r WHERE r.memberId = :memberId AND r.pointType = :type AND r.isExpired = false")
    Integer sumPointsByMemberIdAndType(@Param("memberId") String memberId, @Param("type") PointType type);
    
    @Query("SELECT COALESCE(SUM(r.pointAmount), 0) FROM PointRecord r WHERE r.createdAt >= :start AND r.createdAt < :end AND r.pointType = :type")
    Integer sumPointsByDateRangeAndType(@Param("start") LocalDateTime start, @Param("end") LocalDateTime end, @Param("type") PointType type);
    
    @Query("SELECT COUNT(r) FROM PointRecord r WHERE r.createdAt >= :start AND r.createdAt < :end AND r.pointType = :type")
    Long countPointsByDateRangeAndType(@Param("start") LocalDateTime start, @Param("end") LocalDateTime end, @Param("type") PointType type);
}
