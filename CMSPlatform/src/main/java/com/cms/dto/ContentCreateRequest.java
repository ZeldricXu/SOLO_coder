package com.cms.dto;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Size;
import java.util.List;

public class ContentCreateRequest {

    @NotBlank(message = "内容标题不能为空")
    @Size(max = 200, message = "内容标题长度不能超过200")
    private String contentTitle;

    private String contentType;

    @NotBlank(message = "内容正文不能为空")
    private String contentBody;

    private String contentAuthor;

    private String contentCategory;

    private List<String> contentTags;

    private String templateId;

    public String getContentTitle() {
        return contentTitle;
    }

    public void setContentTitle(String contentTitle) {
        this.contentTitle = contentTitle;
    }

    public String getContentType() {
        return contentType;
    }

    public void setContentType(String contentType) {
        this.contentType = contentType;
    }

    public String getContentBody() {
        return contentBody;
    }

    public void setContentBody(String contentBody) {
        this.contentBody = contentBody;
    }

    public String getContentAuthor() {
        return contentAuthor;
    }

    public void setContentAuthor(String contentAuthor) {
        this.contentAuthor = contentAuthor;
    }

    public String getContentCategory() {
        return contentCategory;
    }

    public void setContentCategory(String contentCategory) {
        this.contentCategory = contentCategory;
    }

    public List<String> getContentTags() {
        return contentTags;
    }

    public void setContentTags(List<String> contentTags) {
        this.contentTags = contentTags;
    }

    public String getTemplateId() {
        return templateId;
    }

    public void setTemplateId(String templateId) {
        this.templateId = templateId;
    }
}
