package com.orchestration.scheduler.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.orchestration.common.exception.BusinessException;
import com.orchestration.common.util.JsonUtil;
import com.orchestration.persistence.entity.TaskDefinition;
import com.orchestration.persistence.entity.TaskInstance;
import com.orchestration.persistence.mapper.TaskDefinitionMapper;
import com.orchestration.persistence.mapper.TaskInstanceMapper;
import com.orchestration.scheduler.dto.TaskInstanceVO;
import com.orchestration.scheduler.dto.TaskSubmitRequest;
import com.orchestration.scheduler.engine.TaskExecutionEngine;
import com.orchestration.scheduler.graph.TaskGraph;
import com.orchestration.scheduler.graph.TaskGraphBuilder;
import com.orchestration.scheduler.graph.TaskNode;
import com.orchestration.scheduler.service.TaskSchedulerService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class TaskSchedulerServiceImpl implements TaskSchedulerService {

    private final TaskDefinitionMapper taskDefinitionMapper;
    private final TaskInstanceMapper taskInstanceMapper;
    private final TaskExecutionEngine executionEngine;
    private final TaskGraphBuilder graphBuilder;

    @Override
    public String submitTask(TaskSubmitRequest request) {
        TaskDefinition task = taskDefinitionMapper.selectOne(
                new LambdaQueryWrapper<TaskDefinition>()
                        .eq(TaskDefinition::getTaskCode, request.getTaskCode())
                        .eq(TaskDefinition::getStatus, 1)
        );
        if (task == null) {
            throw new BusinessException("任务不存在或未启用");
        }

        TaskGraph graph = graphBuilder.buildGraph(task.getId());
        Map<Long, Map<String, Object>> inputDataMap = new HashMap<>();
        inputDataMap.put(task.getId(), request.getInputData());

        executionEngine.executeGraphAsync(graph, inputDataMap);

        return task.getId().toString();
    }

    @Override
    public String submitTaskWithDependencies(Long taskId, Map<Long, Map<String, Object>> inputDataMap) {
        TaskDefinition task = taskDefinitionMapper.selectById(taskId);
        if (task == null) {
            throw new BusinessException("任务不存在");
        }

        TaskGraph graph = graphBuilder.buildGraph(taskId);
        executionEngine.executeGraphAsync(graph, inputDataMap);

        return taskId.toString();
    }

    @Override
    public TaskInstanceVO getInstanceStatus(String instanceNo) {
        TaskInstance instance = taskInstanceMapper.selectOne(
                new LambdaQueryWrapper<TaskInstance>().eq(TaskInstance::getInstanceNo, instanceNo)
        );
        if (instance == null) {
            throw new BusinessException("任务实例不存在");
        }
        return convertToVO(instance);
    }

    @Override
    public List<TaskInstanceVO> getTaskInstances(Long taskId, Integer page, Integer size) {
        Page<TaskInstance> pageResult = taskInstanceMapper.selectPage(
                Page.of(page, size),
                new LambdaQueryWrapper<TaskInstance>()
                        .eq(TaskInstance::getTaskId, taskId)
                        .orderByDesc(TaskInstance::getCreatedAt)
        );
        return pageResult.getRecords().stream()
                .map(this::convertToVO)
                .collect(Collectors.toList());
    }

    @Override
    public boolean cancelTask(String instanceNo) {
        TaskInstance instance = taskInstanceMapper.selectOne(
                new LambdaQueryWrapper<TaskInstance>().eq(TaskInstance::getInstanceNo, instanceNo)
        );
        if (instance == null) {
            throw new BusinessException("任务实例不存在");
        }
        if (!Arrays.asList("pending", "running").contains(instance.getStatus())) {
            throw new BusinessException("任务已完成或已取消，无法取消");
        }
        instance.setStatus("cancelled");
        instance.setCompletedAt(java.time.LocalDateTime.now());
        return taskInstanceMapper.updateById(instance) > 0;
    }

    @Override
    public boolean retryTask(String instanceNo) {
        TaskInstance instance = taskInstanceMapper.selectOne(
                new LambdaQueryWrapper<TaskInstance>().eq(TaskInstance::getInstanceNo, instanceNo)
        );
        if (instance == null) {
            throw new BusinessException("任务实例不存在");
        }
        if (!"failed".equals(instance.getStatus())) {
            throw new BusinessException("只有失败的任务才能重试");
        }

        TaskDefinition task = taskDefinitionMapper.selectById(instance.getTaskId());
        if (task == null) {
            throw new BusinessException("任务定义不存在");
        }

        TaskGraph graph = graphBuilder.buildGraph(task.getId());
        Map<String, Object> inputData = instance.getInputData() != null
                ? JsonUtil.fromJson(instance.getInputData(), Map.class)
                : new HashMap<>();

        Map<Long, Map<String, Object>> inputDataMap = new HashMap<>();
        inputDataMap.put(task.getId(), inputData);

        executionEngine.executeGraphAsync(graph, inputDataMap);

        return true;
    }

    @Override
    public Map<String, Object> getTaskGraph(Long taskId) {
        TaskGraph graph = graphBuilder.buildGraph(taskId);
        List<TaskNode> nodes = graph.topologicalSort();

        List<Map<String, Object>> nodeList = new ArrayList<>();
        List<Map<String, Object>> edgeList = new ArrayList<>();

        for (TaskNode node : nodes) {
            Map<String, Object> nodeMap = new HashMap<>();
            nodeMap.put("id", node.getTaskId());
            nodeMap.put("name", node.getTaskName());
            nodeMap.put("code", node.getTaskCode());
            nodeMap.put("type", node.getTaskType());
            nodeMap.put("inDegree", node.getInDegree());
            nodeList.add(nodeMap);

            for (TaskNode dep : node.getDependencies()) {
                Map<String, Object> edgeMap = new HashMap<>();
                edgeMap.put("source", dep.getTaskId());
                edgeMap.put("target", node.getTaskId());
                edgeList.add(edgeMap);
            }
        }

        Map<String, Object> result = new HashMap<>();
        result.put("nodes", nodeList);
        result.put("edges", edgeList);
        result.put("hasCycle", graph.hasCycle());
        result.put("nodeCount", nodes.size());

        return result;
    }

    private TaskInstanceVO convertToVO(TaskInstance instance) {
        TaskInstanceVO vo = new TaskInstanceVO();
        BeanUtils.copyProperties(instance, vo);
        return vo;
    }
}
