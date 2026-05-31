package com.apishield.classification.policy.impl;

import com.apishield.classification.domain.ClassificationPolicy;
import com.apishield.classification.domain.DataClassification;
import com.apishield.classification.policy.PolicyEngine;
import com.apishield.domain.vo.SecurityLevel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class DefaultPolicyEngine implements PolicyEngine {

    @Override
    public SecurityLevel evaluate(DataClassification classification, ClassificationPolicy policy) {
        if (policy.getCategoryLevelMap().containsKey(classification.getDataCategory())) {
            return policy.getCategoryLevelMap().get(classification.getDataCategory());
        }
        return policy.getDefaultLevel();
    }

    @Override
    public void applyPolicy(DataClassification classification, ClassificationPolicy policy) {
        SecurityLevel evaluatedLevel = evaluate(classification, policy);
        if (evaluatedLevel != classification.getSecurityLevel()) {
            log.info("Updating security level for {} from {} to {} based on policy {}",
                    classification.getColumnName(),
                    classification.getSecurityLevel(),
                    evaluatedLevel,
                    policy.getPolicyName());
            classification.setSecurityLevel(evaluatedLevel);
        }
        classification.setPolicyId(policy.getPolicyId());
    }
}
