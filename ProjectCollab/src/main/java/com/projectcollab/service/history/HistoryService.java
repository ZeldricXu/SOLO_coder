package com.projectcollab.service.history;

import com.projectcollab.entity.HistoryRecord;
import com.projectcollab.entity.Task;
import com.projectcollab.repository.HistoryRecordRepository;
import com.projectcollab.util.IdGenerator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class HistoryService {

    @Autowired
    private HistoryRecordRepository historyRepository;

    public List<HistoryRecord> getHistoryByProjectId(String projectId) {
        return historyRepository.findByProjectIdOrderByCreatedAtDesc(projectId);
    }

    public List<HistoryRecord> getHistoryByTaskId(String taskId) {
        return historyRepository.findByTaskId(taskId);
    }

    public List<HistoryRecord> getHistoryByDocId(String docId) {
        return historyRepository.findByDocId(docId);
    }

    public List<HistoryRecord> getHistoryByUserId(String userId) {
        return historyRepository.findByUserId(userId);
    }

    @Transactional
    public HistoryRecord recordTaskCreation(Task task) {
        HistoryRecord record = new HistoryRecord();
        record.setHistoryId(IdGenerator.generateHistoryId());
        record.setProjectId(task.getProject().getProjectId());
        record.setTaskId(task.getTaskId());
        record.setActionType("task_created");
        record.setActionContent("任务创建: " + task.getTaskName());
        record.setUserId(task.getTaskAssignee());
        record.setCreatedAt(LocalDateTime.now());
        return historyRepository.save(record);
    }

    @Transactional
    public HistoryRecord recordProgressUpdate(Task task) {
        HistoryRecord record = new HistoryRecord();
        record.setHistoryId(IdGenerator.generateHistoryId());
        record.setProjectId(task.getProject().getProjectId());
        record.setTaskId(task.getTaskId());
        record.setActionType("progress_updated");
        record.setActionContent("任务进度更新: " + task.getTaskName() + " -> " + task.getTaskProgress() + "%");
        record.setCreatedAt(LocalDateTime.now());
        return historyRepository.save(record);
    }

    @Transactional
    public HistoryRecord recordTaskCompletion(Task task) {
        HistoryRecord record = new HistoryRecord();
        record.setHistoryId(IdGenerator.generateHistoryId());
        record.setProjectId(task.getProject().getProjectId());
        record.setTaskId(task.getTaskId());
        record.setActionType("task_completed");
        record.setActionContent("任务完成: " + task.getTaskName());
        record.setUserId(task.getTaskAssignee());
        record.setCreatedAt(LocalDateTime.now());
        return historyRepository.save(record);
    }

    @Transactional
    public HistoryRecord recordDocumentUpload(String projectId, String docId, String docName, String uploader) {
        HistoryRecord record = new HistoryRecord();
        record.setHistoryId(IdGenerator.generateHistoryId());
        record.setProjectId(projectId);
        record.setDocId(docId);
        record.setActionType("document_uploaded");
        record.setActionContent("文档上传: " + docName);
        record.setUserId(uploader);
        record.setCreatedAt(LocalDateTime.now());
        return historyRepository.save(record);
    }

    @Transactional
    public HistoryRecord recordProjectCreation(String projectId, String projectName) {
        HistoryRecord record = new HistoryRecord();
        record.setHistoryId(IdGenerator.generateHistoryId());
        record.setProjectId(projectId);
        record.setActionType("project_created");
        record.setActionContent("项目创建: " + projectName);
        record.setCreatedAt(LocalDateTime.now());
        return historyRepository.save(record);
    }
}
