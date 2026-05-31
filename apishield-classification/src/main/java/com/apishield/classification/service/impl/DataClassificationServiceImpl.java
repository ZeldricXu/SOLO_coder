package com.apishield.classification.service.impl;

import com.apishield.classification.domain.ClassificationPolicy;
import com.apishield.classification.domain.DataClassification;
import com.apishield.classification.domain.ScanJob;
import com.apishield.classification.dto.PolicyRequest;
import com.apishield.classification.dto.ScanJobRequest;
import com.apishield.classification.policy.PolicyEngine;
import com.apishield.classification.scanner.DataScanner;
import com.apishield.classification.service.DataClassificationService;
import com.apishield.common.exception.BusinessException;
import com.apishield.common.util.IdGenerator;
import com.apishield.domain.vo.SecurityLevel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class DataClassificationServiceImpl implements DataClassificationService {

    private final List<DataScanner> scanners;
    private final PolicyEngine policyEngine;
    
    private final Map<String, ScanJob> jobStore = new ConcurrentHashMap<>();
    private final Map<String, List<DataClassification>> resultStore = new ConcurrentHashMap<>();
    private final Map<String, ClassificationPolicy> policyStore = new ConcurrentHashMap<>();

    @Override
    public ScanJob createScanJob(ScanJobRequest request) {
        ScanJob job = new ScanJob();
        job.setId(IdGenerator.generateId("scan"));
        job.setJobId(job.getId());
        job.setJobName(request.getJobName());
        job.setDataSource(request.getDataSource());
        job.setTables(request.getTables() != null ? request.getTables() : new ArrayList<>());
        job.setStatus("CREATED");
        job.setCreatedAt(LocalDateTime.now());
        job.setUpdatedAt(LocalDateTime.now());

        jobStore.put(job.getJobId(), job);
        resultStore.put(job.getJobId(), new ArrayList<>());
        
        log.info("Created scan job: {}", job.getJobId());
        return job;
    }

    @Override
    public ScanJob startScanJob(String jobId) {
        ScanJob job = getScanJob(jobId);
        if (!"CREATED".equals(job.getStatus())) {
            throw new BusinessException("CLASSIFY_001", "任务状态不允许启动: " + job.getStatus());
        }

        job.setStatus("RUNNING");
        job.setStartTime(LocalDateTime.now());
        job.setUpdatedAt(LocalDateTime.now());

        DataScanner scanner = scanners.stream()
                .filter(s -> s.supports(job.getDataSource()))
                .findFirst()
                .orElseThrow(() -> new BusinessException("CLASSIFY_001", "不支持的数据源: " + job.getDataSource()));

        try {
            List<DataClassification> results = scanner.scan(job);
            resultStore.put(job.getJobId(), results);
            
            job.setTotalScanned(results.size() * 10);
            job.setSensitiveFound(results.size());
            job.setStatus("COMPLETED");
            job.setEndTime(LocalDateTime.now());
            
            applyDefaultPolicy(results);
            
            log.info("Scan job {} completed, found {} sensitive columns", jobId, results.size());
        } catch (Exception e) {
            job.setStatus("FAILED");
            job.setErrorMessage(e.getMessage());
            job.setEndTime(LocalDateTime.now());
            log.error("Scan job {} failed: {}", jobId, e.getMessage());
        }

        job.setUpdatedAt(LocalDateTime.now());
        return job;
    }

    @Override
    public ScanJob getScanJob(String jobId) {
        ScanJob job = jobStore.get(jobId);
        if (job == null) {
            throw new BusinessException("NOT_FOUND", "扫描任务不存在: " + jobId);
        }
        return job;
    }

    @Override
    public List<DataClassification> getClassificationResults(String jobId) {
        return resultStore.getOrDefault(jobId, Collections.emptyList());
    }

    @Override
    public List<DataClassification> getClassificationsByDataSource(String dataSource) {
        return resultStore.values().stream()
                .flatMap(List::stream)
                .filter(d -> dataSource.equals(d.getDataSource()))
                .collect(Collectors.toList());
    }

    @Override
    public List<DataClassification> getClassificationsByLevel(SecurityLevel level) {
        return resultStore.values().stream()
                .flatMap(List::stream)
                .filter(d -> level == d.getSecurityLevel())
                .collect(Collectors.toList());
    }

    @Override
    public ClassificationPolicy createPolicy(PolicyRequest request) {
        ClassificationPolicy policy = new ClassificationPolicy();
        policy.setId(IdGenerator.generateId("policy"));
        policy.setPolicyId(policy.getId());
        policy.setPolicyName(request.getPolicyName());
        policy.setDescription(request.getDescription());
        policy.setDefaultLevel(request.getDefaultLevel() != null ? request.getDefaultLevel() : SecurityLevel.INTERNAL);
        policy.setPriority(request.getPriority());
        policy.setEnabled(true);
        policy.setCreatedAt(LocalDateTime.now());
        policy.setUpdatedAt(LocalDateTime.now());
        
        if (request.getCategoryLevelMap() != null) {
            policy.getCategoryLevelMap().putAll(request.getCategoryLevelMap());
        }
        if (request.getRules() != null) {
            policy.getRules().putAll(request.getRules());
        }

        policyStore.put(policy.getPolicyId(), policy);
        log.info("Created classification policy: {}", policy.getPolicyId());
        return policy;
    }

    @Override
    public ClassificationPolicy getPolicy(String policyId) {
        ClassificationPolicy policy = policyStore.get(policyId);
        if (policy == null) {
            throw new BusinessException("NOT_FOUND", "策略不存在: " + policyId);
        }
        return policy;
    }

    @Override
    public List<ClassificationPolicy> getAllPolicies() {
        return new ArrayList<>(policyStore.values());
    }

    @Override
    public ClassificationPolicy updatePolicy(String policyId, PolicyRequest request) {
        ClassificationPolicy policy = getPolicy(policyId);
        policy.setPolicyName(request.getPolicyName());
        policy.setDescription(request.getDescription());
        if (request.getDefaultLevel() != null) {
            policy.setDefaultLevel(request.getDefaultLevel());
        }
        policy.setPriority(request.getPriority());
        policy.setUpdatedAt(LocalDateTime.now());
        
        if (request.getCategoryLevelMap() != null) {
            policy.getCategoryLevelMap().clear();
            policy.getCategoryLevelMap().putAll(request.getCategoryLevelMap());
        }
        if (request.getRules() != null) {
            policy.getRules().clear();
            policy.getRules().putAll(request.getRules());
        }
        
        return policy;
    }

    @Override
    public void deletePolicy(String policyId) {
        policyStore.remove(policyId);
        log.info("Deleted classification policy: {}", policyId);
    }

    @Override
    public void applyPolicyToClassification(String classificationId, String policyId) {
        ClassificationPolicy policy = getPolicy(policyId);
        
        DataClassification classification = resultStore.values().stream()
                .flatMap(List::stream)
                .filter(d -> classificationId.equals(d.getClassificationId()))
                .findFirst()
                .orElseThrow(() -> new BusinessException("NOT_FOUND", "分类结果不存在: " + classificationId));

        policyEngine.applyPolicy(classification, policy);
    }

    @Override
    public Map<String, SecurityLevel> getSensitiveFields(String tableName) {
        Map<String, SecurityLevel> fields = new HashMap<>();
        resultStore.values().stream()
                .flatMap(List::stream)
                .filter(d -> tableName.equals(d.getTableName()))
                .forEach(d -> fields.put(d.getColumnName(), d.getSecurityLevel()));
        return fields;
    }

    private void applyDefaultPolicy(List<DataClassification> results) {
        ClassificationPolicy defaultPolicy = policyStore.values().stream()
                .filter(ClassificationPolicy::isEnabled)
                .min(Comparator.comparingInt(ClassificationPolicy::getPriority))
                .orElse(null);

        if (defaultPolicy != null) {
            for (DataClassification result : results) {
                policyEngine.applyPolicy(result, defaultPolicy);
            }
        }
    }
}
