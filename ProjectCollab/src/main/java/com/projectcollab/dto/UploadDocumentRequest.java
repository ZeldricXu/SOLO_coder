package com.projectcollab.dto;

public class UploadDocumentRequest {
    
    private String projectId;
    private String docName;
    private String docType;
    private long docSize;
    private String docUploader;
    private boolean shareWithMembers;

    public UploadDocumentRequest() {
    }

    public String getProjectId() {
        return projectId;
    }

    public void setProjectId(String projectId) {
        this.projectId = projectId;
    }

    public String getDocName() {
        return docName;
    }

    public void setDocName(String docName) {
        this.docName = docName;
    }

    public String getDocType() {
        return docType;
    }

    public void setDocType(String docType) {
        this.docType = docType;
    }

    public long getDocSize() {
        return docSize;
    }

    public void setDocSize(long docSize) {
        this.docSize = docSize;
    }

    public String getDocUploader() {
        return docUploader;
    }

    public void setDocUploader(String docUploader) {
        this.docUploader = docUploader;
    }

    public boolean isShareWithMembers() {
        return shareWithMembers;
    }

    public void setShareWithMembers(boolean shareWithMembers) {
        this.shareWithMembers = shareWithMembers;
    }
}
