package com.projmanage.controller;

import com.projmanage.dto.ApiResponse;
import com.projmanage.model.Task;
import com.projmanage.service.TaskService;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/tasks/sort")
public class TaskSortController {

    private final TaskService taskService;

    public TaskSortController(TaskService taskService) {
        this.taskService = taskService;
    }

    @GetMapping("/composite")
    public ApiResponse<Map<String, Object>> getTasksSortedByCompositeScore(@RequestParam String projectId) {
        List<Task> tasks = taskService.getTasksSortedByCompositeScore(projectId);
        Map<String, Object> result = new HashMap<>();
        result.put("tasks", tasks);
        result.put("sort_type", "composite");
        result.put("description", "综合排序（优先级50% + 紧迫度35% + 工作负载15%）");
        return ApiResponse.success(result);
    }

    @GetMapping("/priority")
    public ApiResponse<Map<String, Object>> getTasksSortedByPriority(@RequestParam String projectId) {
        List<Task> tasks = taskService.getTasksSortedByPriority(projectId);
        Map<String, Object> result = new HashMap<>();
        result.put("tasks", tasks);
        result.put("sort_type", "priority");
        result.put("description", "按优先级排序（高优先级优先）");
        return ApiResponse.success(result);
    }

    @GetMapping("/urgency")
    public ApiResponse<Map<String, Object>> getTasksSortedByUrgency(@RequestParam String projectId) {
        List<Task> tasks = taskService.getTasksSortedByUrgency(projectId);
        Map<String, Object> result = new HashMap<>();
        result.put("tasks", tasks);
        result.put("sort_type", "urgency");
        result.put("description", "按紧迫度排序（截止时间越近越紧急）");
        return ApiResponse.success(result);
    }

    @GetMapping("/workload")
    public ApiResponse<Map<String, Object>> getTasksSortedByWorkload(@RequestParam String projectId) {
        List<Task> tasks = taskService.getTasksSortedByWorkload(projectId);
        Map<String, Object> result = new HashMap<>();
        result.put("tasks", tasks);
        result.put("sort_type", "workload");
        result.put("description", "按工作负载排序（负责人负载越高越优先）");
        return ApiResponse.success(result);
    }

    @GetMapping("/due-date")
    public ApiResponse<Map<String, Object>> getTasksSortedByDueDate(@RequestParam String projectId) {
        List<Task> tasks = taskService.getTasksSortedByDueDate(projectId);
        Map<String, Object> result = new HashMap<>();
        result.put("tasks", tasks);
        result.put("sort_type", "due_date");
        result.put("description", "按截止日期排序（越早截止越优先）");
        return ApiResponse.success(result);
    }

    @GetMapping("/multi-dimensional")
    public ApiResponse<Map<String, Object>> getTasksMultiDimensionalSort(
            @RequestParam String projectId,
            @RequestParam(required = false) String primarySort,
            @RequestParam(required = false) String secondarySort) {
        if (primarySort == null || primarySort.isEmpty()) {
            primarySort = "priority_desc";
        }

        List<Task> tasks = taskService.getTasksMultiDimensionalSort(projectId, primarySort, secondarySort);
        Map<String, Object> result = new HashMap<>();
        result.put("tasks", tasks);
        result.put("primary_sort", primarySort);
        result.put("secondary_sort", secondarySort);
        result.put("available_sort_options", new String[]{
                "priority_desc", "priority_asc",
                "due_date", "due_date_desc",
                "assignee_load", "progress"
        });
        return ApiResponse.success(result);
    }

    @GetMapping("/high-priority")
    public ApiResponse<Map<String, Object>> getHighPriorityTasks(@RequestParam String projectId) {
        List<Task> tasks = taskService.getHighPriorityTasks(projectId);
        Map<String, Object> result = new HashMap<>();
        result.put("tasks", tasks);
        result.put("filter", "high_priority_pending");
        return ApiResponse.success(result);
    }
}
