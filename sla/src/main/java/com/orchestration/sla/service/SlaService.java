package com.orchestration.sla.service;

import com.orchestration.persistence.entity.SlaPolicy;
import com.orchestration.persistence.entity.SlaRecord;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

public interface SlaService {

    Long createPolicy(SlaPolicy policy);

    boolean updatePolicy(SlaPolicy policy);

    SlaPolicy getPolicy(Long id);

    List<SlaPolicy> listPolicies(Integer page, Integer size);

    boolean deletePolicy(Long id);

    Long createSlaRecord(Long taskInstanceId, Long policyId);

    SlaRecord getSlaRecord(Long taskInstanceId);

    List<SlaRecord> listOvertimeRecords(Integer page, Integer size);

    void checkAndEscalateSla();

    Map<String, Object> calculateRemainingTime(Long recordId);

    boolean notifyEscalation(Long recordId, Integer level);
}
