package com.survey.repository;

import com.survey.entity.SurveyTemplate;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface SurveyTemplateRepository extends JpaRepository<SurveyTemplate, String> {

    Optional<SurveyTemplate> findByTemplateId(String templateId);

    List<SurveyTemplate> findByTemplateType(String templateType);

    List<SurveyTemplate> findByTemplateStatus(String templateStatus);
}
