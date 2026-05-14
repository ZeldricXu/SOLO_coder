package com.projmanage.repository;

import com.projmanage.model.TaskSortScore;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface TaskSortScoreRepository extends JpaRepository<TaskSortScore, String> {
    Optional<TaskSortScore> findByTaskId(String taskId);
    List<TaskSortScore> findByProjectIdOrderByCompositeScoreDesc(String projectId);
}
