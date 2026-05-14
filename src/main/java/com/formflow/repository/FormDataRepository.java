package com.formflow.repository;

import com.formflow.entity.FormData;
import com.formflow.enums.FormStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface FormDataRepository extends JpaRepository<FormData, Long> {

    Optional<FormData> findByFormId(String formId);

    List<FormData> findByTemplateId(String templateId);

    List<FormData> findBySubmitterId(String submitterId);

    List<FormData> findBySubmitterIdOrderBySubmitTimeDesc(String submitterId);

    List<FormData> findByTemplateIdAndSubmitterId(String templateId, String submitterId);

    List<FormData> findByStatus(FormStatus status);

    Optional<FormData> findByProcessInstanceId(String processInstanceId);

    @Query("SELECT f FROM FormData f WHERE f.templateId = :templateId AND f.status = :status")
    List<FormData> findByTemplateIdAndStatus(@Param("templateId") String templateId, @Param("status") FormStatus status);

    @Query("SELECT COUNT(f) FROM FormData f WHERE f.templateId = :templateId")
    Long countByTemplateId(@Param("templateId") String templateId);

    @Query("SELECT COUNT(f) FROM FormData f WHERE f.templateId = :templateId AND f.status = :status")
    Long countByTemplateIdAndStatus(@Param("templateId") String templateId, @Param("status") FormStatus status);

    @Query("SELECT COUNT(f) FROM FormData f WHERE f.submitTime BETWEEN :startTime AND :endTime")
    Long countBySubmitTimeBetween(@Param("startTime") LocalDateTime startTime, @Param("endTime") LocalDateTime endTime);

    @Query("SELECT COUNT(f) FROM FormData f WHERE f.status = :status")
    Long countByStatus(@Param("status") FormStatus status);
}
