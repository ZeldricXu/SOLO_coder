package com.flowplatform.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.flowplatform.entity.FormDefinition;
import com.flowplatform.mapper.FormDefinitionMapper;
import com.flowplatform.service.FormDefinitionService;
import org.springframework.stereotype.Service;
import java.util.List;

@Service
public class FormDefinitionServiceImpl extends ServiceImpl<FormDefinitionMapper, FormDefinition> implements FormDefinitionService {

    @Override
    public List<FormDefinition> listByCreator(Long creatorId) {
        return list(new LambdaQueryWrapper<FormDefinition>()
                .eq(FormDefinition::getCreatorId, creatorId)
                .orderByDesc(FormDefinition::getUpdateTime));
    }

    @Override
    public FormDefinition getByFormKey(String formKey) {
        return getOne(new LambdaQueryWrapper<FormDefinition>().eq(FormDefinition::getFormKey, formKey));
    }

    @Override
    public boolean publishForm(Long formId) {
        FormDefinition form = getById(formId);
        if (form != null) {
            form.setStatus(1);
            return updateById(form);
        }
        return false;
    }

    @Override
    public boolean unpublishForm(Long formId) {
        FormDefinition form = getById(formId);
        if (form != null) {
            form.setStatus(0);
            return updateById(form);
        }
        return false;
    }
}
