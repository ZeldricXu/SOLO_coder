package com.apishield.classification.service;

import com.apishield.application.service.ApplicationService;
import com.apishield.classification.domain.ClassificationPolicy;
import com.apishield.classification.domain.DataClassification;
import com.apishield.classification.domain.ScanJob;
import com.apishield.classification.dto.PolicyRequest;
import com.apishield.classification.dto.ScanJobRequest;
import com.apishield.domain.vo.SecurityLevel;
import java.util.List;
import java.util.Map;

public interface DataClassificationService extends ApplicationService {
    ScanJob createScanJob(ScanJobRequest request);
    ScanJob startScanJob(String jobId);
    ScanJob getScanJob(String jobId);
    List<DataClassification> getClassificationResults(String jobId);
    List<DataClassification> getClassificationsByDataSource(String dataSource);
    List<DataClassification> getClassificationsByLevel(SecurityLevel level);
    
    ClassificationPolicy createPolicy(PolicyRequest request);
    ClassificationPolicy getPolicy(String policyId);
    List<ClassificationPolicy> getAllPolicies();
    ClassificationPolicy updatePolicy(String policyId, PolicyRequest request);
    void deletePolicy(String policyId);
    
    void applyPolicyToClassification(String classificationId, String policyId);
    Map<String, SecurityLevel> getSensitiveFields(String tableName);
}
