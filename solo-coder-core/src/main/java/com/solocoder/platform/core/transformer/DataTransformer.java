package com.solocoder.platform.core.transformer;

import com.solocoder.platform.core.model.DataRecord;
import com.solocoder.platform.core.model.TransformRule;

import java.util.List;

public interface DataTransformer {

    DataRecord transform(DataRecord record, TransformRule rule);

    List<DataRecord> transformBatch(List<DataRecord> records, List<TransformRule> rules);
}
