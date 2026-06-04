package com.flowplatform.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.flowplatform.entity.FormDefinition;
import java.util.List;

public interface FormDefinitionService extends IService<FormDefinition> {
    List<FormDefinition> listByCreator(Long creatorId);
    FormDefinition getByFormKey(String formKey);
    boolean publishForm(Long formId);
    boolean unpublishForm(Long formId);
}
