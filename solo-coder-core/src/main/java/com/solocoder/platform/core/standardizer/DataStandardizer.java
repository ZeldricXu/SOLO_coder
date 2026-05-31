package com.solocoder.platform.core.standardizer;

import com.solocoder.platform.core.model.DataRecord;
import com.solocoder.platform.core.model.StandardizationRule;

import java.util.List;

public interface DataStandardizer {

    DataRecord standardize(DataRecord record, StandardizationRule rule);

    List<DataRecord> standardizeBatch(List<DataRecord> records, List<StandardizationRule> rules);
}
