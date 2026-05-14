package com.datamigrate.repository;

import com.datamigrate.entity.MappingRule;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface MappingRuleRepository extends JpaRepository<MappingRule, Long> {

    List<MappingRule> findByTask_TaskIdOrderByRuleOrderAsc(String taskId);

    void deleteByTask_TaskId(String taskId);
}
