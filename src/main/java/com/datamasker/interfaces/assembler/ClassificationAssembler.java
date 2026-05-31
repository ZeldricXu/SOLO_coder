package com.datamasker.interfaces.assembler;

import com.datamasker.domain.classification.model.DataField;
import com.datamasker.domain.classification.model.ScanResult;
import com.datamasker.interfaces.dto.classification.ScanResponse;

import java.util.List;
import java.util.stream.Collectors;

public class ClassificationAssembler {

    public static ScanResponse toScanResponse(ScanResult scanResult) {
        ScanResponse response = new ScanResponse();
        response.setDataSource(scanResult.getDataSource());
        response.setTotalFields(scanResult.getTotalFields());
        response.setClassifiedFields(scanResult.getClassifiedFields());
        response.setSensitiveFields(scanResult.getSensitiveFields());
        response.setResults(toFieldClassifications(scanResult.getResults()));
        return response;
    }

    public static ScanResponse.FieldClassification toFieldClassification(DataField dataField) {
        ScanResponse.FieldClassification fc = new ScanResponse.FieldClassification();
        fc.setFieldName(dataField.getFieldName());
        fc.setCategory(dataField.getCategory());
        fc.setLevel(dataField.getLevel());
        fc.setConfidence(dataField.getConfidence());
        return fc;
    }

    private static List<ScanResponse.FieldClassification> toFieldClassifications(List<DataField> dataFields) {
        return dataFields.stream()
                .map(ClassificationAssembler::toFieldClassification)
                .collect(Collectors.toList());
    }
}
