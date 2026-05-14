package com.formflow.repository;

import com.formflow.entity.FormTemplate;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface FormTemplateRepository extends JpaRepository<FormTemplate, Long> {

    Optional<FormTemplate> findByTemplateId(String templateId);

    Optional<FormTemplate> findByTemplateIdAndEnabledTrue(String templateId);

    List<FormTemplate> findByEnabledTrue();

    boolean existsByTemplateId(String templateId);

    List<FormTemplate> findByCreatorId(String creatorId);
}
