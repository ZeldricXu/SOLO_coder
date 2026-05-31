package com.orchestration.scheduler.graph;

import com.orchestration.persistence.entity.TaskDefinition;
import com.orchestration.persistence.entity.TaskDependency;
import com.orchestration.persistence.mapper.TaskDefinitionMapper;
import com.orchestration.persistence.mapper.TaskDependencyMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import java.util.List;

@Component
@RequiredArgsConstructor
public class TaskGraphBuilder {

    private final TaskDefinitionMapper taskDefinitionMapper;
    private final TaskDependencyMapper taskDependencyMapper;

    public TaskGraph buildGraph(Long taskId) {
        TaskGraph graph = new TaskGraph();
        buildGraphRecursive(taskId, graph, new java.util.HashSet<>());
        return graph;
    }

    private void buildGraphRecursive(Long taskId, TaskGraph graph, java.util.Set<Long> visited) {
        if (visited.contains(taskId)) {
            return;
        }
        visited.add(taskId);

        TaskDefinition task = taskDefinitionMapper.selectById(taskId);
        if (task == null) {
            return;
        }

        TaskNode node = new TaskNode(task.getId(), task.getTaskCode(), task.getTaskName(), task.getTaskType());
        graph.addNode(node);

        List<TaskDependency> dependencies = taskDependencyMapper.selectList(
                new LambdaQueryWrapper<TaskDependency>().eq(TaskDependency::getTaskId, taskId)
        );

        for (TaskDependency dep : dependencies) {
            buildGraphRecursive(dep.getDependentTaskId(), graph, visited);
            TaskNode depNode = graph.getNode(dep.getDependentTaskId());
            if (depNode != null) {
                node.addDependency(depNode);
            }
        }
    }

    public TaskGraph buildGraphForBatch(List<Long> taskIds) {
        TaskGraph graph = new TaskGraph();
        java.util.Set<Long> visited = new java.util.HashSet<>();

        for (Long taskId : taskIds) {
            buildGraphRecursive(taskId, graph, visited);
        }

        return graph;
    }
}
