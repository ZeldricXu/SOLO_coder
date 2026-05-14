package com.projectcollab.service.document;

import com.projectcollab.dto.DocumentShareTask;
import com.projectcollab.dto.UploadDocumentRequest;
import com.projectcollab.entity.Document;
import com.projectcollab.entity.Project;
import com.projectcollab.exception.ProjectCollabException;
import com.projectcollab.repository.DocumentRepository;
import com.projectcollab.service.analysis.AnalysisService;
import com.projectcollab.service.history.HistoryService;
import com.projectcollab.service.project.ProjectService;
import com.projectcollab.service.queue.DocumentShareQueueService;
import com.projectcollab.util.IdGenerator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Service
public class DocumentService {

    @Autowired
    private DocumentRepository documentRepository;

    @Autowired
    private ProjectService projectService;

    @Autowired
    private DocumentShareQueueService queueService;

    @Autowired
    private HistoryService historyService;

    @Autowired
    private AnalysisService analysisService;

    public List<Document> getDocumentsByProjectId(String projectId) {
        return documentRepository.findByProject_ProjectId(projectId);
    }

    public List<Document> getSharedDocuments(String projectId) {
        return documentRepository.findByProject_ProjectIdAndShared(projectId, true);
    }

    public Optional<Document> getDocumentById(String docId) {
        return documentRepository.findById(docId);
    }

    @Transactional
    public Document uploadDocument(UploadDocumentRequest request) {
        Project project = projectService.getProjectOrThrow(request.getProjectId());

        Document document = new Document();
        document.setDocId(IdGenerator.generateDocId());
        document.setProject(project);
        document.setDocName(request.getDocName());
        document.setDocType(request.getDocType() != null ? request.getDocType() : "general");
        document.setDocSize(request.getDocSize());
        document.setDocUploader(request.getDocUploader() != null ? request.getDocUploader() : "system");
        document.setUploadedAt(LocalDateTime.now());
        document.setDocPath("/documents/" + document.getDocId());
        document.setShared(request.isShareWithMembers());

        Document savedDoc = documentRepository.save(document);

        if (request.isShareWithMembers()) {
            enqueueShareTask(savedDoc, project);
        }

        historyService.recordDocumentUpload(
                project.getProjectId(),
                savedDoc.getDocId(),
                savedDoc.getDocName(),
                savedDoc.getDocUploader()
        );

        analysisService.updateDocumentStatistics(project.getProjectId());

        return savedDoc;
    }

    private void enqueueShareTask(Document document, Project project) {
        DocumentShareTask task = new DocumentShareTask(
                document.getDocId(),
                document.getDocName(),
                project.getProjectId(),
                document.getDocUploader()
        );
        queueService.enqueueTask(task);
    }

    public DocumentShareQueueService getQueueService() {
        return queueService;
    }

    @Transactional
    public Document shareDocument(String docId) {
        Document document = documentRepository.findById(docId)
                .orElseThrow(() -> new ProjectCollabException(404, "文档不存在: " + docId));
        
        document.setShared(true);
        Document saved = documentRepository.save(document);
        
        enqueueShareTask(saved, saved.getProject());
        
        return saved;
    }
}
