package com.projectcollab.dto;

public class UploadDocumentResponse {
    
    private String docId;
    private String status;

    public UploadDocumentResponse() {
    }

    public UploadDocumentResponse(String docId, String status) {
        this.docId = docId;
        this.status = status;
    }

    public String getDocId() {
        return docId;
    }

    public void setDocId(String docId) {
        this.docId = docId;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }
}
