package com.llmgateway.modelregistry.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.llmgateway.common.api.PageResult;
import com.llmgateway.common.constant.CommonConstants;
import com.llmgateway.common.exception.BusinessException;
import com.llmgateway.common.util.IdGenerator;
import com.llmgateway.modelregistry.dto.ModelRegisterDTO;
import com.llmgateway.modelregistry.entity.Model;
import com.llmgateway.modelregistry.mapper.ModelMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDateTime;

@Slf4j
@Service
@RequiredArgsConstructor
public class ModelService {

    private final ModelMapper modelMapper;

    @Transactional(rollbackFor = Exception.class)
    public Model register(ModelRegisterDTO dto) {
        LambdaQueryWrapper<Model> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Model::getModelName, dto.getModelName())
                .eq(Model::getDeleted, 0);
        if (modelMapper.selectCount(wrapper) > 0) {
            throw new BusinessException("模型名称已存在");
        }

        Model model = new Model();
        model.setModelId(IdGenerator.generateModelId());
        model.setModelName(dto.getModelName());
        model.setModelType(dto.getModelType());
        model.setProvider(dto.getProvider());
        model.setDescription(dto.getDescription());
        model.setTaskType(dto.getTaskType());
        model.setBaseModel(dto.getBaseModel());
        model.setLicense(dto.getLicense());
        model.setTags(dto.getTags());
        model.setOwner(dto.getOwner());

        modelMapper.insert(model);
        log.info("模型注册成功: modelId={}", model.getModelId());
        return model;
    }

    public Model getById(String modelId) {
        Model model = modelMapper.selectById(modelId);
        if (model == null) {
            throw new BusinessException(404, "模型不存在");
        }
        return model;
    }

    public PageResult<Model> list(String provider, String modelType, Integer pageNum, Integer pageSize) {
        LambdaQueryWrapper<Model> wrapper = new LambdaQueryWrapper<>();
        if (provider != null) {
            wrapper.eq(Model::getProvider, provider);
        }
        if (modelType != null) {
            wrapper.eq(Model::getModelType, modelType);
        }
        wrapper.eq(Model::getDeleted, 0);
        wrapper.orderByDesc(Model::getCreatedAt);

        IPage<Model> page = modelMapper.selectPage(new Page<>(pageNum, pageSize), wrapper);
        return PageResult.of(page);
    }

    @Transactional(rollbackFor = Exception.class)
    public Model update(String modelId, ModelRegisterDTO dto) {
        Model model = getById(modelId);
        model.setDescription(dto.getDescription());
        model.setTaskType(dto.getTaskType());
        model.setTags(dto.getTags());
        model.setOwner(dto.getOwner());
        model.setUpdatedAt(LocalDateTime.now());
        modelMapper.updateById(model);
        return model;
    }

    @Transactional(rollbackFor = Exception.class)
    public void delete(String modelId) {
        Model model = getById(modelId);
        model.setDeleted(1);
        model.setUpdatedAt(LocalDateTime.now());
        modelMapper.updateById(model);
    }
}
