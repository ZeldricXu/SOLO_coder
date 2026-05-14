package com.example.mailservice.repository;

import com.example.mailservice.model.MailTemplate;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface MailTemplateRepository extends JpaRepository<MailTemplate, Long> {
    Optional<MailTemplate> findByTemplateId(String templateId);

    Optional<MailTemplate> findByTemplateName(String templateName);

    List<MailTemplate> findByEnabledTrue();
}
