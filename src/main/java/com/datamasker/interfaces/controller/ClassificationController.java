package com.datamasker.interfaces.controller;

import com.datamasker.application.service.ClassificationService;
import com.datamasker.domain.classification.model.DataField;
import com.datamasker.domain.classification.model.ScanResult;
import com.datamasker.interfaces.assembler.ClassificationAssembler;
import com.datamasker.interfaces.dto.Result;
import com.datamasker.interfaces.dto.classification.ReclassifyRequest;
import com.datamasker.interfaces.dto.classification.ScanRequest;
import com.datamasker.interfaces.dto.classification.ScanResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/classification")
@RequiredArgsConstructor
public class ClassificationController {

    private final ClassificationService classificationService;

    @PostMapping("/scan")
    public Result<ScanResponse> scan(@Valid @RequestBody ScanRequest request) {
        ScanResult scanResult = classificationService.scanDataSource(
                request.getDataSource(), request.getFields());
        ScanResponse response = ClassificationAssembler.toScanResponse(scanResult);
        return Result.success(response);
    }

    @GetMapping("/results/{dataSource}")
    public Result<ScanResponse> getResults(@PathVariable String dataSource) {
        List<DataField> fields = classificationService.getClassificationResults(dataSource);
        ScanResponse response = new ScanResponse();
        response.setDataSource(dataSource);
        response.setResults(fields.stream()
                .map(ClassificationAssembler::toFieldClassification)
                .toList());
        response.setTotalFields(fields.size());
        response.setSensitiveFields((int) fields.stream()
                .filter(f -> !"PUBLIC".equals(f.getLevel()))
                .count());
        response.setClassifiedFields(fields.size());
        return Result.success(response);
    }

    @PutMapping("/results/{dataSource}/{fieldName}")
    public Result<Void> reclassify(@PathVariable String dataSource,
                                   @PathVariable String fieldName,
                                   @Valid @RequestBody ReclassifyRequest request) {
        classificationService.reclassifyField(
                dataSource, fieldName, request.getCategory(), request.getLevel());
        return Result.success(null);
    }
}
