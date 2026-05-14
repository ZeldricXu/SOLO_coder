package com.projectcollab.repository;

import com.projectcollab.entity.Document;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface DocumentRepository extends JpaRepository<Document, String> {
    List<Document> findByProject_ProjectId(String projectId);
    List<Document> findByDocUploader(String uploader);
    List<Document> findByProject_ProjectIdAndShared(String projectId, boolean shared);
}
