package com.healthtrack.repository;

import com.healthtrack.entity.DeduplicationConfig;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface DeduplicationConfigRepository extends JpaRepository<DeduplicationConfig, Long> {
    
    Optional<DeduplicationConfig> findByPriority(String priority);
    
    List<DeduplicationConfig> findByEnabledTrue();
    
    Optional<DeduplicationConfig> findByPriorityAndEnabledTrue(String priority);
}
