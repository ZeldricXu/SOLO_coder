package com.datamasker.application.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.datamasker.domain.classification.model.DataField;
import com.datamasker.domain.classification.model.ScanResult;
import com.datamasker.domain.classification.scanner.DataScanner;
import com.datamasker.infrastructure.config.ClassificationConfig;
import com.datamasker.infrastructure.persistence.entity.ClassificationResultEntity;
import com.datamasker.infrastructure.persistence.mapper.ClassificationResultMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ClassificationService {

    private final DataScanner dataScanner;
    private final ClassificationConfig classificationConfig;
    private final ClassificationResultMapper classificationResultMapper;

    public ScanResult scanDataSource(String dataSource, Map<String, String> fields) {
        ScanResult scanResult = dataScanner.scanBatch(dataSource, fields);

        for (DataField field : scanResult.getResults()) {
            ClassificationResultEntity entity = new ClassificationResultEntity();
            entity.setDataSource(field.getDataSource());
            entity.setFieldName(field.getFieldName());
            entity.setCategory(field.getCategory());
            entity.setLevel(field.getLevel());
            entity.setConfidence(field.getConfidence());
            entity.setCreatedAt(LocalDateTime.now());
            classificationResultMapper.insert(entity);
        }

        return scanResult;
    }

    public List<DataField> getClassificationResults(String dataSource) {
        LambdaQueryWrapper<ClassificationResultEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ClassificationResultEntity::getDataSource, dataSource);
        List<ClassificationResultEntity> entities = classificationResultMapper.selectList(wrapper);
        return entities.stream().map(this::toDataField).collect(Collectors.toList());
    }

    public DataField getFieldClassification(String dataSource, String fieldName) {
        LambdaQueryWrapper<ClassificationResultEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ClassificationResultEntity::getDataSource, dataSource)
               .eq(ClassificationResultEntity::getFieldName, fieldName);
        ClassificationResultEntity entity = classificationResultMapper.selectOne(wrapper);
        if (entity == null) {
            return null;
        }
        return toDataField(entity);
    }

    public void reclassifyField(String dataSource, String fieldName, String newCategory, String newLevel) {
        LambdaUpdateWrapper<ClassificationResultEntity> wrapper = new LambdaUpdateWrapper<>();
        wrapper.eq(ClassificationResultEntity::getDataSource, dataSource)
               .eq(ClassificationResultEntity::getFieldName, fieldName)
               .set(ClassificationResultEntity::getCategory, newCategory)
               .set(ClassificationResultEntity::getLevel, newLevel)
               .set(ClassificationResultEntity::getConfidence, 1.0);
        classificationResultMapper.update(null, wrapper);
    }

    private DataField toDataField(ClassificationResultEntity entity) {
        DataField field = new DataField();
        field.setDataSource(entity.getDataSource());
        field.setFieldName(entity.getFieldName());
        field.setCategory(entity.getCategory());
        field.setLevel(entity.getLevel());
        field.setConfidence(entity.getConfidence() != null ? entity.getConfidence() : 0.0);
        return field;
    }
}
