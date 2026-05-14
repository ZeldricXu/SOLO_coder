package com.example.mailservice.repository;

import com.example.mailservice.model.MailRecord;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface MailRecordRepository extends JpaRepository<MailRecord, Long> {
    Optional<MailRecord> findByMailId(String mailId);

    Page<MailRecord> findByMailType(String mailType, Pageable pageable);

    Page<MailRecord> findByCategory(String category, Pageable pageable);

    Page<MailRecord> findBySender(String sender, Pageable pageable);

    List<MailRecord> findByMailStatus(String mailStatus);

    List<MailRecord> findByCreatedAtBetween(LocalDateTime start, LocalDateTime end);

    @Query("SELECT m FROM MailRecord m WHERE m.subject LIKE %:keyword% OR m.content LIKE %:keyword%")
    Page<MailRecord> searchByKeyword(@Param("keyword") String keyword, Pageable pageable);

    @Query("SELECT COUNT(m) FROM MailRecord m WHERE m.mailType = :mailType AND m.createdAt BETWEEN :start AND :end")
    long countByMailTypeAndCreatedAtBetween(@Param("mailType") String mailType,
                                             @Param("start") LocalDateTime start,
                                             @Param("end") LocalDateTime end);

    @Query("SELECT COUNT(m) FROM MailRecord m WHERE m.mailStatus = :status AND m.createdAt BETWEEN :start AND :end")
    long countByMailStatusAndCreatedAtBetween(@Param("status") String status,
                                               @Param("start") LocalDateTime start,
                                               @Param("end") LocalDateTime end);

    @Query("SELECT m FROM MailRecord m WHERE " +
           "(:keyword IS NULL OR m.subject LIKE %:keyword% OR m.content LIKE %:keyword%) AND " +
           "(:category IS NULL OR m.category = :category) AND " +
           "(:sender IS NULL OR m.sender LIKE %:sender%) AND " +
           "(:mailType IS NULL OR m.mailType = :mailType) AND " +
           "(:mailStatus IS NULL OR m.mailStatus = :mailStatus) AND " +
           "(:startTime IS NULL OR m.createdAt >= :startTime) AND " +
           "(:endTime IS NULL OR m.createdAt <= :endTime)")
    Page<MailRecord> searchMails(@Param("keyword") String keyword,
                                  @Param("category") String category,
                                  @Param("sender") String sender,
                                  @Param("mailType") String mailType,
                                  @Param("mailStatus") String mailStatus,
                                  @Param("startTime") LocalDateTime startTime,
                                  @Param("endTime") LocalDateTime endTime,
                                  Pageable pageable);
}
