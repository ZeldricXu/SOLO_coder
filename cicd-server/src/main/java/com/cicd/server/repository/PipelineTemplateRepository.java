package com.cicd.server.repository;

import com.cicd.server.entity.PipelineTemplate;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public interface PipelineTemplateRepository extends JpaRepository<PipelineTemplate, Long> {

    List<PipelineTemplate> findByCategory(String category);

    List<PipelineTemplate> findByIsBuiltinTrue();

    List<PipelineTemplate> findByIsBuiltinFalse();
}
