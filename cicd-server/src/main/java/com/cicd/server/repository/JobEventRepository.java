package com.cicd.server.repository;

import com.cicd.server.entity.JobEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;

@Repository
public interface JobEventRepository extends JpaRepository<JobEvent, Long> {

    List<JobEvent> findByJobIdOrderByEventTimestampAsc(Long jobId);

    List<JobEvent> findByJobTokenOrderByEventTimestampAsc(String jobToken);

    @Query("SELECT MAX(e.eventTimestamp) FROM JobEvent e WHERE e.jobId = ?1")
    Optional<java.time.LocalDateTime> findLastEventTimestamp(Long jobId);

    @Query("SELECT e FROM JobEvent e WHERE e.jobId = ?1 AND e.eventType = ?2 ORDER BY e.eventTimestamp DESC LIMIT 1")
    Optional<JobEvent> findLatestByJobIdAndEventType(Long jobId, String eventType);

    @Query("SELECT e FROM JobEvent e WHERE e.jobId = ?1 ORDER BY e.eventTimestamp DESC")
    List<JobEvent> findByJobIdOrderByEventTimestampDesc(Long jobId);

    boolean existsByJobToken(String jobToken);
}
