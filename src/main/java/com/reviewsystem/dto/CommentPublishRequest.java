package com.reviewsystem.dto;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Size;

public class CommentPublishRequest {

    @NotBlank(message = "内容ID不能为空")
    private String contentId;

    @NotBlank(message = "用户ID不能为空")
    private String userId;

    @NotBlank(message = "评论内容不能为空")
    @Size(max = 2000, message = "评论内容不能超过2000字")
    private String commentContent;

    private String commentType = "text";

    public CommentPublishRequest() {}

    public String getContentId() {
        return contentId;
    }

    public void setContentId(String contentId) {
        this.contentId = contentId;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getCommentContent() {
        return commentContent;
    }

    public void setCommentContent(String commentContent) {
        this.commentContent = commentContent;
    }

    public String getCommentType() {
        return commentType;
    }

    public void setCommentType(String commentType) {
        this.commentType = commentType;
    }
}
