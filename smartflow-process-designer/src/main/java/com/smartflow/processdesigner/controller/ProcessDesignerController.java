package com.smartflow.processdesigner.controller;

import com.smartflow.common.base.Result;
import com.smartflow.persistence.entity.ProcessDefinition;
import com.smartflow.persistence.entity.ProcessInstance;
import com.smartflow.persistence.entity.ProcessLine;
import com.smartflow.persistence.entity.ProcessNode;
import com.smartflow.processdesigner.service.ProcessDesignerService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/process")
@RequiredArgsConstructor
public class ProcessDesignerController {

    private final ProcessDesignerService processDesignerService;

    @PostMapping("/designer/validate")
    public Result<Map<String, Object>> validateProcess(
            @RequestBody Map<String, Object> data) {
        List<ProcessNode> nodes = com.alibaba.fastjson2.JSON.parseArray(
            com.alibaba.fastjson2.JSON.toJSONString(data.get("nodes")), ProcessNode.class);
        List<ProcessLine> lines = com.alibaba.fastjson2.JSON.parseArray(
            com.alibaba.fastjson2.JSON.toJSONString(data.get("lines")), ProcessLine.class);
        Map<String, Object> result = processDesignerService.validateProcess(nodes, lines);
        return Result.success(result);
    }

    @PostMapping("/designer/create")
    public Result<Map<String, Object>> createProcess(@RequestBody Map<String, Object> data) {
        ProcessDefinition process = com.alibaba.fastjson2.JSON.parseObject(
            com.alibaba.fastjson2.JSON.toJSONString(data.get("process")), ProcessDefinition.class);
        List<ProcessNode> nodes = com.alibaba.fastjson2.JSON.parseArray(
            com.alibaba.fastjson2.JSON.toJSONString(data.get("nodes")), ProcessNode.class);
        List<ProcessLine> lines = com.alibaba.fastjson2.JSON.parseArray(
            com.alibaba.fastjson2.JSON.toJSONString(data.get("lines")), ProcessLine.class);
        Map<String, Object> result = processDesignerService.createProcess(process, nodes, lines);
        return Result.success(result);
    }

    @GetMapping("/definition/{definitionId}")
    public Result<ProcessDefinition> getProcessDefinition(@PathVariable Long definitionId) {
        ProcessDefinition definition = processDesignerService.getProcessDefinition(definitionId);
        return Result.success(definition);
    }

    @GetMapping("/definition/{definitionId}/detail")
    public Result<Map<String, Object>> getProcessDetail(@PathVariable Long definitionId) {
        Map<String, Object> detail = processDesignerService.getProcessDetail(definitionId);
        return Result.success(detail);
    }

    @GetMapping("/definition/list")
    public Result<List<ProcessDefinition>> listProcessDefinitions(
            @RequestParam(required = false) String processType,
            @RequestParam(required = false) String category) {
        List<ProcessDefinition> definitions = processDesignerService.listProcessDefinitions(processType, category);
        return Result.success(definitions);
    }

    @PostMapping("/instance/start")
    public Result<ProcessInstance> startProcess(
            @RequestParam Long definitionId,
            @RequestParam(required = false) Long businessId,
            @RequestParam(required = false) String businessType,
            @RequestBody(required = false) Map<String, Object> variables) {
        ProcessInstance instance = processDesignerService.startProcess(definitionId, businessId, businessType, variables);
        return Result.success(instance);
    }

    @PostMapping("/instance/{instanceId}/execute")
    public Result<Map<String, Object>> executeNode(
            @PathVariable Long instanceId,
            @RequestParam Long nodeId,
            @RequestBody(required = false) Map<String, Object> variables) {
        Map<String, Object> result = processDesignerService.executeNode(instanceId, nodeId, variables);
        return Result.success(result);
    }

    @GetMapping("/instance/{instanceId}")
    public Result<ProcessInstance> getProcessInstance(@PathVariable Long instanceId) {
        ProcessInstance instance = processDesignerService.getProcessInstance(instanceId);
        return Result.success(instance);
    }

    @PostMapping("/instance/{instanceId}/terminate")
    public Result<Boolean> terminateProcess(@PathVariable Long instanceId) {
        boolean success = processDesignerService.terminateProcess(instanceId);
        return Result.success(success);
    }
}
