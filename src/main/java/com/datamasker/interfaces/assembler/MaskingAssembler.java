package com.datamasker.interfaces.assembler;

import com.datamasker.domain.masking.model.MaskingResult;
import com.datamasker.domain.masking.model.MaskingRule;
import com.datamasker.interfaces.dto.masking.MaskDataResponse;
import com.datamasker.interfaces.dto.masking.RuleResponse;

import java.util.List;

public class MaskingAssembler {

    public static MaskDataResponse toMaskDataResponse(List<MaskingResult> results) {
        MaskDataResponse response = new MaskDataResponse();
        response.setResults(results.stream().map(MaskingAssembler::toMaskedField).toList());
        return response;
    }

    public static RuleResponse toRuleResponse(MaskingRule rule) {
        RuleResponse response = new RuleResponse();
        response.setRuleId(rule.getRuleId());
        response.setFieldPattern(rule.getFieldPattern());
        response.setStrategy(rule.getStrategy().name());
        response.setLevelRequired(rule.getLevelRequired());
        response.setParams(rule.getParams());
        response.setEnabled(rule.isEnabled());
        return response;
    }

    public static MaskDataResponse.MaskedField toMaskedField(MaskingResult result) {
        MaskDataResponse.MaskedField field = new MaskDataResponse.MaskedField();
        field.setFieldName(result.getFieldName());
        field.setMaskedValue(result.getMaskedValue());
        field.setStrategy(result.getStrategy());
        field.setWasMasked(result.isWasMasked());
        return field;
    }
}
