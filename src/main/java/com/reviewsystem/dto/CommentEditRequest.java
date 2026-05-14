package com.reviewsystem.dto;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Size;

public class CommentEditRequest {

    @NotBlank(message = "评论ID不能为空")
    private String commentId;

    @NotBlank(message = "评论内容不能为空")
    @Size(max = 2000, message = "评论内容不能超过2000字")
    private String commentContent;

    private String userId;

    public CommentEditRequest() {}

    public String getCommentId() {
        return commentId;
    }

    public void setCommentId(String commentId) {
        this.commentId = commentId;
    }

    public String getCommentContent() {
        return commentContent;
    }

    public void setCommentContent(String commentContent) {
        this.commentContent = commentContent;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }
}
