package com.example.mailservice.repository;

import com.example.mailservice.model.ArchiveRecord;
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
public interface ArchiveRecordRepository extends JpaRepository<ArchiveRecord, Long> {
    Optional<ArchiveRecord> findByArchiveId(String archiveId);

    Optional<ArchiveRecord> findByMailId(String mailId);

    Page<ArchiveRecord> findByCategory(String category, Pageable pageable);

    List<ArchiveRecord> findByMailIdIn(List<String> mailIds);

    @Query("SELECT COUNT(a) FROM ArchiveRecord a WHERE a.archiveTime BETWEEN :start AND :end")
    long countByArchiveTimeBetween(@Param("start") LocalDateTime start,
                                    @Param("end") LocalDateTime end);

    @Query("SELECT COUNT(a) FROM ArchiveRecord a WHERE a.category = :category")
    long countByCategory(@Param("category") String category);
}
