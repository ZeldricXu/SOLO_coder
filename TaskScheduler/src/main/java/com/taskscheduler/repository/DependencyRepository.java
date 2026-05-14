package com.taskscheduler.repository;

import com.taskscheduler.entity.Dependency;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public interface DependencyRepository extends JpaRepository<Dependency, Long> {

    List<Dependency> findByTaskId(String taskId);

    List<Dependency> findByDependsOn(String dependsOn);

    @Query("SELECT d.dependsOn FROM Dependency d WHERE d.taskId = :taskId")
    List<String> findDependenciesByTaskId(@Param("taskId") String taskId);

    @Query("SELECT d.taskId FROM Dependency d WHERE d.dependsOn = :dependsOn")
    List<String> findDependentTasks(@Param("dependsOn") String dependsOn);

    void deleteByTaskId(String taskId);

    boolean existsByTaskIdAndDependsOn(String taskId, String dependsOn);
}
