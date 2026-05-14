package com.reviewsystem.dto;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Size;

public class ReplyRequest {

    @NotBlank(message = "评论ID不能为空")
    private String commentId;

    @NotBlank(message = "回复用户不能为空")
    private String replyUser;

    @NotBlank(message = "回复内容不能为空")
    @Size(max = 1000, message = "回复内容不能超过1000字")
    private String replyContent;

    private String parentReplyId;

    public ReplyRequest() {}

    public String getCommentId() {
        return commentId;
    }

    public void setCommentId(String commentId) {
        this.commentId = commentId;
    }

    public String getReplyUser() {
        return replyUser;
    }

    public void setReplyUser(String replyUser) {
        this.replyUser = replyUser;
    }

    public String getReplyContent() {
        return replyContent;
    }

    public void setReplyContent(String replyContent) {
        this.replyContent = replyContent;
    }

    public String getParentReplyId() {
        return parentReplyId;
    }

    public void setParentReplyId(String parentReplyId) {
        this.parentReplyId = parentReplyId;
    }
}
