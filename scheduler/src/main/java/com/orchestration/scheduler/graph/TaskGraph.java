package com.orchestration.scheduler.graph;

import lombok.Data;
import java.util.*;

@Data
public class TaskGraph {

    private Map<Long, TaskNode> nodes = new LinkedHashMap<>();

    public void addNode(TaskNode node) {
        nodes.put(node.getTaskId(), node);
    }

    public TaskNode getNode(Long taskId) {
        return nodes.get(taskId);
    }

    public List<TaskNode> getStartNodes() {
        List<TaskNode> startNodes = new ArrayList<>();
        for (TaskNode node : nodes.values()) {
            if (node.getInDegree() == 0) {
                startNodes.add(node);
            }
        }
        return startNodes;
    }

    public List<TaskNode> topologicalSort() {
        List<TaskNode> result = new ArrayList<>();
        Map<Long, Integer> inDegree = new HashMap<>();
        Queue<TaskNode> queue = new LinkedList<>();

        for (TaskNode node : nodes.values()) {
            inDegree.put(node.getTaskId(), node.getInDegree());
            if (node.getInDegree() == 0) {
                queue.offer(node);
            }
        }

        while (!queue.isEmpty()) {
            TaskNode node = queue.poll();
            result.add(node);

            for (TaskNode dependent : node.getDependents()) {
                int newDegree = inDegree.get(dependent.getTaskId()) - 1;
                inDegree.put(dependent.getTaskId(), newDegree);
                if (newDegree == 0) {
                    queue.offer(dependent);
                }
            }
        }

        if (result.size() != nodes.size()) {
            throw new IllegalStateException("任务图中存在循环依赖");
        }

        return result;
    }

    public boolean hasCycle() {
        try {
            topologicalSort();
            return false;
        } catch (IllegalStateException e) {
            return true;
        }
    }
}
