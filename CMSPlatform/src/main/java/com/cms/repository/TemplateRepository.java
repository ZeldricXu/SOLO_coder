package com.cms.repository;

import com.cms.entity.Template;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface TemplateRepository extends JpaRepository<Template, String> {

    Optional<Template> findByTemplateName(String templateName);

    List<Template> findByTemplateStatus(String templateStatus);

    List<Template> findByTemplateType(String templateType);
}
