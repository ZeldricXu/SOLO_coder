package com.solocoder.platform.core.pipeline;

import com.solocoder.platform.core.model.DataRecord;
import com.solocoder.platform.core.model.StandardizationRule;
import com.solocoder.platform.core.model.TransformRule;
import com.solocoder.platform.core.standardizer.DataStandardizer;
import com.solocoder.platform.core.transformer.DataTransformer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class ProcessingPipeline {

    private final DataTransformer dataTransformer;
    private final DataStandardizer dataStandardizer;

    public List<DataRecord> process(List<DataRecord> records,
                                    List<TransformRule> transformRules,
                                    List<StandardizationRule> standardizationRules) {
        log.info("Processing pipeline started: records={}, transformRules={}, standardizationRules={}",
                records.size(), transformRules.size(), standardizationRules.size());

        List<DataRecord> transformed = dataTransformer.transformBatch(records, transformRules);
        log.info("Transform phase completed: input={}, output={}", records.size(), transformed.size());

        List<DataRecord> standardized = dataStandardizer.standardizeBatch(transformed, standardizationRules);
        log.info("Standardization phase completed: output={}", standardized.size());

        return standardized;
    }

    public DataRecord processSingle(DataRecord record,
                                    List<TransformRule> transformRules,
                                    List<StandardizationRule> standardizationRules) {
        DataRecord current = record;
        for (TransformRule rule : transformRules) {
            current = dataTransformer.transform(current, rule);
            if (current == null) return null;
        }
        for (StandardizationRule rule : standardizationRules) {
            current = dataStandardizer.standardize(current, rule);
        }
        return current;
    }
}
