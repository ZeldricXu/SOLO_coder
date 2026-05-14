package com.finance.repository;

import com.finance.entity.CategoryMatchTask;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface CategoryMatchTaskRepository extends JpaRepository<CategoryMatchTask, String> {
    List<CategoryMatchTask> findByTaskStatusOrderByCreatedAtAsc(String taskStatus);
    List<CategoryMatchTask> findByAccountIdAndTaskStatus(String accountId, String taskStatus);
    List<CategoryMatchTask> findByTaskStatusAndRetryCountLessThan(String taskStatus, Integer maxRetryCount);
    Optional<CategoryMatchTask> findByRecordId(String recordId);
    Long countByTaskStatus(String taskStatus);
}
