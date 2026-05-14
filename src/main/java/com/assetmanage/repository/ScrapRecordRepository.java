package com.assetmanage.repository;

import com.assetmanage.entity.ScrapRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ScrapRecordRepository extends JpaRepository<ScrapRecord, String> {

    List<ScrapRecord> findByAssetId(String assetId);

    List<ScrapRecord> findByScrapStatus(String scrapStatus);

    Optional<ScrapRecord> findByAssetIdAndScrapStatus(String assetId, String scrapStatus);
}
