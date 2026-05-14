package com.cms.repository;

import com.cms.entity.PublishRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface PublishRecordRepository extends JpaRepository<PublishRecord, String> {

    List<PublishRecord> findByContentId(String contentId);

    List<PublishRecord> findByPublishStatus(String publishStatus);

    List<PublishRecord> findByPublishChannel(String publishChannel);

    @Query("SELECT COUNT(p) FROM PublishRecord p WHERE p.publishStatus = :status")
    long countByStatus(@Param("status") String status);

    @Query("SELECT COUNT(p) FROM PublishRecord p WHERE p.contentId = :contentId AND p.publishStatus = :status")
    long countByContentIdAndStatus(@Param("contentId") String contentId, @Param("status") String status);
}
