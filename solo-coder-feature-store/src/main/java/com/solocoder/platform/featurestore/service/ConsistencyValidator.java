package com.solocoder.platform.featurestore.service;

import com.solocoder.platform.featurestore.model.ConsistencyReport;

public interface ConsistencyValidator {

    ConsistencyReport validate(String featureId);

    ConsistencyReport validate(String featureId, java.util.List<String> entityIds);
}
