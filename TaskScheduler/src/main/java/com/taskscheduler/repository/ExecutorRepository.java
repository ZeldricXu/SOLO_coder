package com.taskscheduler.repository;

import com.taskscheduler.entity.Executor;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;

@Repository
public interface ExecutorRepository extends JpaRepository<Executor, String> {

    Optional<Executor> findByExecutorId(String executorId);

    @Query("SELECT e FROM Executor e WHERE e.executorStatus = 'online' ORDER BY e.currentLoad ASC")
    List<Executor> findAvailableExecutors();

    @Query("SELECT e FROM Executor e WHERE e.executorStatus = 'online' AND e.currentLoad < e.maxCapacity ORDER BY e.currentLoad ASC")
    List<Executor> findExecutorsWithAvailableCapacity();

    @Query("SELECT e FROM Executor e WHERE e.executorStatus = 'online' " +
           "AND (e.taskType IS NULL OR e.taskType = :taskType) " +
           "AND e.currentLoad < e.maxCapacity " +
           "ORDER BY e.currentLoad ASC")
    List<Executor> findAvailableExecutorsForTaskType(@Param("taskType") String taskType);

    List<Executor> findByExecutorStatus(String status);

    boolean existsByExecutorId(String executorId);
}
