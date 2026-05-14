package com.configcenter.push.repository;

import com.configcenter.common.entity.PushRecord;
import com.configcenter.common.enums.PushStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface PushRecordRepository extends JpaRepository<PushRecord, String>, JpaSpecificationExecutor<PushRecord> {

    List<PushRecord> findByConfigIdOrderByPushTimeDesc(String configId);

    List<PushRecord> findByTargetGroupOrderByPushTimeDesc(String targetGroup);

    @Query("SELECT p FROM PushRecord p WHERE p.configId = :configId ORDER BY p.pushTime DESC")
    List<PushRecord> findLatestByConfigId(@Param("configId") String configId, org.springframework.data.domain.Pageable pageable);

    List<PushRecord> findByPushStatus(PushStatus status);

    @Query("SELECT p FROM PushRecord p WHERE p.pushStatus IN (:statuses) ORDER BY p.pushTime ASC")
    List<PushRecord> findByStatusesForRetry(@Param("statuses") List<PushStatus> statuses);

    Optional<PushRecord> findFirstByConfigIdOrderByPushTimeDesc(String configId);
}
