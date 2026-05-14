package com.survey.repository;

import com.survey.entity.SurveyType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface SurveyTypeRepository extends JpaRepository<SurveyType, String> {

    Optional<SurveyType> findByTypeCode(String typeCode);

    List<SurveyType> findByTypeStatus(String typeStatus);

    List<SurveyType> findByTypeCategory(String category);

    List<SurveyType> findByIsSystem(Boolean isSystem);
}
