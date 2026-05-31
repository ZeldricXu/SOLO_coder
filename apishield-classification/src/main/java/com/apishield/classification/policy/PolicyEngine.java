package com.apishield.classification.policy;

import com.apishield.classification.domain.ClassificationPolicy;
import com.apishield.classification.domain.DataClassification;
import com.apishield.domain.vo.SecurityLevel;

public interface PolicyEngine {
    SecurityLevel evaluate(DataClassification classification, ClassificationPolicy policy);
    void applyPolicy(DataClassification classification, ClassificationPolicy policy);
}
