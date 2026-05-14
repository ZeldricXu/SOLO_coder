package com.contractmgmt.repository;

import com.contractmgmt.entity.ArchiveRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ArchiveRecordRepository extends JpaRepository<ArchiveRecord, String> {

    Optional<ArchiveRecord> findByArchiveId(String archiveId);

    Optional<ArchiveRecord> findByContractId(String contractId);

    List<ArchiveRecord> findByArchiveOperator(String archiveOperator);

    @Query("SELECT a FROM ArchiveRecord a WHERE a.archiveLocation LIKE %:keyword% OR a.contractSnapshot LIKE %:keyword%")
    List<ArchiveRecord> searchByKeyword(@Param("keyword") String keyword);
}
