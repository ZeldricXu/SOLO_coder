package com.formflow.repository;

import com.formflow.entity.ProcessDefinition;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ProcessDefinitionRepository extends JpaRepository<ProcessDefinition, Long> {

    Optional<ProcessDefinition> findByProcessId(String processId);

    Optional<ProcessDefinition> findByProcessIdAndEnabledTrue(String processId);

    List<ProcessDefinition> findByEnabledTrue();

    boolean existsByProcessId(String processId);

    List<ProcessDefinition> findByCreatorId(String creatorId);
}
