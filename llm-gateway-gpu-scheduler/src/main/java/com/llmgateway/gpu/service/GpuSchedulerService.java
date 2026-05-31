package com.llmgateway.gpu.service;

import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.llmgateway.common.api.PageResult;
import com.llmgateway.common.constant.CommonConstants;
import com.llmgateway.common.exception.BusinessException;
import com.llmgateway.common.util.IdGenerator;
import com.llmgateway.gpu.entity.GpuNode;
import com.llmgateway.gpu.entity.GpuTask;
import com.llmgateway.gpu.mapper.GpuNodeMapper;
import com.llmgateway.gpu.mapper.GpuTaskMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;

@Slf4j
@Service
@RequiredArgsConstructor
public class GpuSchedulerService {

    private final GpuNodeMapper nodeMapper;
    private final GpuTaskMapper taskMapper;
    private final PriorityQueue<GpuTask> taskQueue = new PriorityQueue<>(
            Comparator.comparingInt(GpuTask::getPriority).reversed()
                    .thenComparing(GpuTask::getCreatedAt));

    @Transactional(rollbackFor = Exception.class)
    public GpuTask submitTask(GpuTask task) {
        task.setTaskId(IdGenerator.generateId("gpu"));
        task.setStatus(CommonConstants.STATUS_PENDING);
        task.setQueuedAt(LocalDateTime.now());
        task.setProgress(0.0);
        taskMapper.insert(task);
        taskQueue.offer(task);
        log.info("GPU任务提交成功: taskId={}, priority={}", task.getTaskId(), task.getPriority());
        return task;
    }

    @Scheduled(fixedDelay = 5000)
    @Transactional(rollbackFor = Exception.class)
    public void scheduleTasks() {
        List<GpuTask> pendingTasks = taskMapper.findPendingTasks();
        for (GpuTask task : pendingTasks) {
            try {
                allocateTask(task);
            } catch (Exception e) {
                log.warn("任务调度失败: taskId={}", task.getTaskId(), e);
            }
        }
    }

    private void allocateTask(GpuTask task) {
        List<GpuNode> availableNodes = nodeMapper.findAvailableNodes(
                task.getRequiredMemoryGb(), task.getRequiredGpuCount());

        if (availableNodes.isEmpty()) {
            return;
        }

        GpuNode selectedNode = availableNodes.get(0);
        List<Integer> gpuIndices = new ArrayList<>();
        for (int i = 0; i < task.getRequiredGpuCount(); i++) {
            gpuIndices.add(i);
        }

        task.setNodeId(selectedNode.getNodeId());
        task.setGpuIndices(gpuIndices);
        task.setStatus(CommonConstants.STATUS_RUNNING);
        task.setStartedAt(LocalDateTime.now());
        taskMapper.updateById(task);

        selectedNode.setAvailableMemoryGb(selectedNode.getAvailableMemoryGb() - task.getRequiredMemoryGb());
        nodeMapper.updateById(selectedNode);

        log.info("任务分配成功: taskId={}, nodeId={}", task.getTaskId(), selectedNode.getNodeId());
        executeTask(task, selectedNode);
    }

    @Transactional(rollbackFor = Exception.class)
    public void executeTask(GpuTask task, GpuNode node) {
        new Thread(() -> {
            try {
                log.info("开始执行GPU任务: taskId={}", task.getTaskId());
                for (int i = 0; i <= 100; i += 10) {
                    task.setProgress(i / 100.0);
                    taskMapper.updateById(task);
                    Thread.sleep(500);
                }
                task.setStatus(CommonConstants.STATUS_SUCCESS);
                task.setProgress(1.0);
                task.setCompletedAt(LocalDateTime.now());
                taskMapper.updateById(task);

                node.setAvailableMemoryGb(node.getAvailableMemoryGb() + task.getRequiredMemoryGb());
                nodeMapper.updateById(node);

                log.info("GPU任务执行完成: taskId={}", task.getTaskId());
            } catch (Exception e) {
                log.error("GPU任务执行失败: taskId={}", task.getTaskId(), e);
                task.setStatus(CommonConstants.STATUS_FAILED);
                task.setErrorDetail(e.getMessage());
                task.setCompletedAt(LocalDateTime.now());
                taskMapper.updateById(task);

                node.setAvailableMemoryGb(node.getAvailableMemoryGb() + task.getRequiredMemoryGb());
                nodeMapper.updateById(node);
            }
        }).start();
    }

    public GpuTask getTask(String taskId) {
        GpuTask task = taskMapper.selectById(taskId);
        if (task == null) {
            throw new BusinessException(404, "GPU任务不存在");
        }
        return task;
    }

    public PageResult<GpuTask> listTasks(String status, Integer pageNum, Integer pageSize) {
        LambdaQueryWrapper<GpuTask> wrapper = new LambdaQueryWrapper<>();
        if (status != null) {
            wrapper.eq(GpuTask::getStatus, status);
        }
        wrapper.eq(GpuTask::getDeleted, 0);
        wrapper.orderByDesc(GpuTask::getPriority).orderByAsc(GpuTask::getCreatedAt);

        IPage<GpuTask> page = taskMapper.selectPage(new Page<>(pageNum, pageSize), wrapper);
        return PageResult.of(page);
    }

    public List<GpuNode> listNodes(String status) {
        LambdaQueryWrapper<GpuNode> wrapper = new LambdaQueryWrapper<>();
        if (status != null) {
            wrapper.eq(GpuNode::getStatus, status);
        }
        wrapper.eq(GpuNode::getDeleted, 0);
        return nodeMapper.selectList(wrapper);
    }

    public GpuNode registerNode(GpuNode node) {
        node.setNodeId(IdGenerator.generateId("node"));
        node.setStatus(node.getStatus() != null ? node.getStatus() : "online");
        nodeMapper.insert(node);
        log.info("GPU节点注册成功: nodeId={}", node.getNodeId());
        return node;
    }

    @Transactional(rollbackFor = Exception.class)
    public boolean preemptTask(String taskId, int higherPriority) {
        GpuTask runningTask = getTask(taskId);
        if (!CommonConstants.STATUS_RUNNING.equals(runningTask.getStatus())) {
            return false;
        }
        if (runningTask.getPriority() >= higherPriority) {
            return false;
        }

        runningTask.setStatus(CommonConstants.STATUS_PENDING);
        runningTask.setProgress(0.0);
        runningTask.setStartedAt(null);
        runningTask.setQueuedAt(LocalDateTime.now());
        taskMapper.updateById(runningTask);

        log.info("任务被抢占: taskId={}", taskId);
        return true;
    }

    @Transactional(rollbackFor = Exception.class)
    public void cancelTask(String taskId) {
        GpuTask task = getTask(taskId);
        if (CommonConstants.STATUS_RUNNING.equals(task.getStatus())) {
            GpuNode node = nodeMapper.selectById(task.getNodeId());
            if (node != null) {
                node.setAvailableMemoryGb(node.getAvailableMemoryGb() + task.getRequiredMemoryGb());
                nodeMapper.updateById(node);
            }
        }
        task.setStatus("cancelled");
        task.setCompletedAt(LocalDateTime.now());
        taskMapper.updateById(task);
    }
}
