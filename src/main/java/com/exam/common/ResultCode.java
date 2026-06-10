package com.exam.common;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum ResultCode {
    SUCCESS(200, "操作成功"),
    ERROR(500, "操作失败"),
    UNAUTHORIZED(401, "未授权"),
    FORBIDDEN(403, "禁止访问"),
    NOT_FOUND(404, "资源不存在"),
    PARAM_ERROR(400, "参数错误"),

    LOGIN_ERROR(1001, "用户名或密码错误"),
    USER_DISABLED(1002, "账号已被禁用"),
    TOKEN_EXPIRED(1003, "Token已过期"),
    TOKEN_INVALID(1004, "Token无效"),

    QUESTION_NOT_FOUND(2001, "题目不存在"),
    QUESTION_DELETE_ERROR(2002, "题目删除失败，已被试卷引用"),
    QUESTION_IMPORT_ERROR(2003, "题目导入失败"),

    EXAM_NOT_FOUND(3001, "考试不存在"),
    EXAM_NOT_STARTED(3002, "考试未开始"),
    EXAM_ENDED(3003, "考试已结束"),
    EXAM_ALREADY_SUBMITTED(3004, "已提交试卷"),
    EXAM_PERMISSION_DENIED(3005, "无考试权限"),

    PAPER_NOT_FOUND(4001, "试卷不存在"),
    PAPER_GENERATE_ERROR(4002, "试卷生成失败"),

    SCREEN_SWITCH_WARNING(5001, "检测到切屏行为"),
    EXAM_ABNORMAL(5002, "考试状态异常"),

    GRADING_ERROR(6001, "评分失败"),
    GRADING_NOT_COMPLETED(6002, "评分未完成"),

    FILE_UPLOAD_ERROR(7001, "文件上传失败"),
    FILE_DOWNLOAD_ERROR(7002, "文件下载失败"),
    FILE_FORMAT_ERROR(7003, "文件格式错误");

    private final Integer code;
    private final String message;
}
