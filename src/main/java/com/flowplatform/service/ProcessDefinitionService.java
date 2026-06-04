package com.flowplatform.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.flowplatform.entity.ProcessDefinition;
import java.util.List;

public interface ProcessDefinitionService extends IService<ProcessDefinition> {
    ProcessDefinition getByProcessKey(String processKey);
    boolean publishProcess(Long processId);
    boolean unpublishProcess(Long processId);
    List<ProcessDefinition> listPublished();
}
