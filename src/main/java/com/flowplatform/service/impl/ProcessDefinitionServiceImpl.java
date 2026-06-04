package com.flowplatform.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.flowplatform.entity.ProcessDefinition;
import com.flowplatform.mapper.ProcessDefinitionMapper;
import com.flowplatform.service.ProcessDefinitionService;
import org.springframework.stereotype.Service;
import java.util.List;

@Service
public class ProcessDefinitionServiceImpl extends ServiceImpl<ProcessDefinitionMapper, ProcessDefinition> implements ProcessDefinitionService {

    @Override
    public ProcessDefinition getByProcessKey(String processKey) {
        return getOne(new LambdaQueryWrapper<ProcessDefinition>().eq(ProcessDefinition::getProcessKey, processKey));
    }

    @Override
    public boolean publishProcess(Long processId) {
        ProcessDefinition process = getById(processId);
        if (process != null) {
            process.setStatus(1);
            return updateById(process);
        }
        return false;
    }

    @Override
    public boolean unpublishProcess(Long processId) {
        ProcessDefinition process = getById(processId);
        if (process != null) {
            process.setStatus(0);
            return updateById(process);
        }
        return false;
    }

    @Override
    public List<ProcessDefinition> listPublished() {
        return list(new LambdaQueryWrapper<ProcessDefinition>()
                .eq(ProcessDefinition::getStatus, 1)
                .orderByDesc(ProcessDefinition::getUpdateTime));
    }
}
