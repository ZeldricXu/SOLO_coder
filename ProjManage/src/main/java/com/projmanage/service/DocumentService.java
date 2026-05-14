package com.projmanage.service;

import com.projmanage.model.Document;
import com.projmanage.repository.DocumentRepository;
import com.projmanage.util.IdGenerator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Service
public class DocumentService {

    private final DocumentRepository documentRepository;

    public DocumentService(DocumentRepository documentRepository) {
        this.documentRepository = documentRepository;
    }

    @Transactional
    public Document uploadDocument(String projectId, String documentName, String documentType,
                                    String filePath, Long fileSize, String uploadedBy) {
        Document document = new Document();
        document.setDocumentId(IdGenerator.generateDocumentId());
        document.setProjectId(projectId);
        document.setDocumentName(documentName);
        document.setDocumentType(documentType);
        document.setFilePath(filePath);
        document.setFileSize(fileSize);
        document.setUploadedBy(uploadedBy);
        document.setVersion("1.0");
        document.setCreatedAt(LocalDateTime.now());

        return documentRepository.save(document);
    }

    public Optional<Document> getDocumentById(String documentId) {
        return documentRepository.findById(documentId);
    }

    public List<Document> getDocumentsByProject(String projectId) {
        return documentRepository.findByProjectIdOrderByCreatedAtDesc(projectId);
    }

    @Transactional
    public Document updateDocument(String documentId, String documentName, String filePath, Long fileSize) {
        Optional<Document> docOpt = documentRepository.findById(documentId);
        if (!docOpt.isPresent()) {
            return null;
        }

        Document document = docOpt.get();
        document.setDocumentName(documentName);
        document.setFilePath(filePath);
        document.setFileSize(fileSize);

        String currentVersion = document.getVersion();
        if (currentVersion != null) {
            String[] parts = currentVersion.split("\\.");
            if (parts.length == 2) {
                int major = Integer.parseInt(parts[0]);
                int minor = Integer.parseInt(parts[1]) + 1;
                document.setVersion(major + "." + minor);
            } else {
                document.setVersion(currentVersion + ".1");
            }
        }

        return documentRepository.save(document);
    }

    @Transactional
    public void deleteDocument(String documentId) {
        documentRepository.deleteById(documentId);
    }
}
