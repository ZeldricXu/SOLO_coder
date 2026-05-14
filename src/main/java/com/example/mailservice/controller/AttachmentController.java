package com.example.mailservice.controller;

import com.example.mailservice.dto.ApiResponse;
import com.example.mailservice.model.MailAttachment;
import com.example.mailservice.service.AttachmentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.File;
import java.util.List;

@Slf4j
@RestController
@RequestMapping("/api/v1/mail/attachment")
@RequiredArgsConstructor
public class AttachmentController {

    private final AttachmentService attachmentService;

    @GetMapping("/mail/{mailId}")
    public ApiResponse<List<MailAttachment>> getAttachments(@PathVariable String mailId) {
        return ApiResponse.success(attachmentService.getAttachmentsByMailId(mailId));
    }

    @GetMapping("/{attachmentId}")
    public ApiResponse<MailAttachment> getAttachmentInfo(@PathVariable String attachmentId) {
        return attachmentService.getAttachmentById(attachmentId)
                .map(ApiResponse::success)
                .orElse(ApiResponse.error(404, "附件不存在"));
    }

    @GetMapping("/{attachmentId}/download")
    public ResponseEntity<Resource> downloadAttachment(@PathVariable String attachmentId) {
        return attachmentService.getAttachmentById(attachmentId)
                .map(attachment -> {
                    File file = new File(attachment.getFilePath());
                    if (!file.exists()) {
                        return ResponseEntity.notFound().build();
                    }

                    Resource resource = new FileSystemResource(file);
                    return ResponseEntity.ok()
                            .contentType(MediaType.parseMediaType(
                                    attachment.getContentType() != null ?
                                            attachment.getContentType() : "application/octet-stream"))
                            .header(HttpHeaders.CONTENT_DISPOSITION,
                                    "attachment; filename=\"" + attachment.getFileName() + "\"")
                            .body(resource);
                })
                .orElse(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{attachmentId}")
    public ApiResponse<String> deleteAttachment(@PathVariable String attachmentId) {
        attachmentService.deleteAttachment(attachmentId);
        return ApiResponse.success(null, "附件删除成功");
    }
}
