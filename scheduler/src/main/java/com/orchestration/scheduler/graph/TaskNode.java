package com.orchestration.scheduler.graph;

import lombok.Data;
import java.util.ArrayList;
import java.util.List;

@Data
public class TaskNode {

    private Long taskId;

    private String taskCode;

    private String taskName;

    private String taskType;

    private List<TaskNode> dependencies = new ArrayList<>();

    private List<TaskNode> dependents = new ArrayList<>();

    private int inDegree = 0;

    public TaskNode(Long taskId, String taskCode, String taskName, String taskType) {
        this.taskId = taskId;
        this.taskCode = taskCode;
        this.taskName = taskName;
        this.taskType = taskType;
    }

    public void addDependency(TaskNode node) {
        if (!dependencies.contains(node)) {
            dependencies.add(node);
            node.getDependents().add(this);
            this.inDegree++;
        }
    }
}
