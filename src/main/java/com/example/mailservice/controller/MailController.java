package com.example.mailservice.controller;

import com.example.mailservice.dto.ApiResponse;
import com.example.mailservice.dto.MailSearchRequest;
import com.example.mailservice.dto.MailSendRequest;
import com.example.mailservice.model.MailRecord;
import com.example.mailservice.service.MailSendService;
import com.example.mailservice.service.SearchService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/v1/mail")
@RequiredArgsConstructor
public class MailController {

    private final MailSendService mailSendService;
    private final SearchService searchService;

    @PostMapping("/send")
    public ApiResponse<Map<String, Object>> sendMail(@RequestBody MailSendRequest request) {
        log.info("收到邮件发送请求，收件人: {}", request.getRecipients());

        if (request.getRecipients() == null || request.getRecipients().isEmpty()) {
            return ApiResponse.error(400, "收件人不能为空");
        }

        MailSendService.SendResult result = mailSendService.sendMail(request);

        Map<String, Object> data = new HashMap<>();
        data.put("mail_id", result.getMailId());
        data.put("status", result.getStatus());
        data.put("message", result.getMessage());

        if (result.isSuccess()) {
            return ApiResponse.success(data);
        } else {
            return ApiResponse.error(500, result.getMessage());
        }
    }

    @PostMapping("/send/async")
    public ApiResponse<Map<String, Object>> sendMailAsync(@RequestBody MailSendRequest request) {
        log.info("收到异步邮件发送请求");

        if (request.getRecipients() == null || request.getRecipients().isEmpty()) {
            return ApiResponse.error(400, "收件人不能为空");
        }

        mailSendService.sendMailAsync(request);

        Map<String, Object> data = new HashMap<>();
        data.put("status", "queued");
        data.put("message", "邮件已加入发送队列");

        return ApiResponse.success(data);
    }

    @GetMapping("/search")
    public ApiResponse<Map<String, Object>> searchMails(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String sender,
            @RequestParam(required = false) String mailType,
            @RequestParam(required = false) String mailStatus,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        log.info("搜索邮件: keyword={}, category={}, page={}, size={}", keyword, category, page, size);

        MailSearchRequest request = MailSearchRequest.builder()
                .keyword(keyword)
                .category(category)
                .sender(sender)
                .mailType(mailType)
                .mailStatus(mailStatus)
                .page(page)
                .size(size)
                .build();

        Page<MailRecord> pageResult = searchService.searchMails(request);

        Map<String, Object> data = new HashMap<>();
        data.put("mails", pageResult.getContent());
        data.put("total", pageResult.getTotalElements());
        data.put("totalPages", pageResult.getTotalPages());
        data.put("currentPage", pageResult.getNumber());
        data.put("pageSize", pageResult.getSize());

        return ApiResponse.success(data);
    }

    @GetMapping("/{mailId}")
    public ApiResponse<MailRecord> getMailById(@PathVariable String mailId) {
        return searchService.getMailByMailId(mailId)
                .map(ApiResponse::success)
                .orElse(ApiResponse.error(404, "邮件不存在"));
    }

    @GetMapping("/category/{category}")
    public ApiResponse<Map<String, Object>> getMailsByCategory(
            @PathVariable String category,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        Page<MailRecord> pageResult = searchService.getMailsByCategory(category, page, size);

        Map<String, Object> data = new HashMap<>();
        data.put("mails", pageResult.getContent());
        data.put("total", pageResult.getTotalElements());
        data.put("totalPages", pageResult.getTotalPages());
        data.put("category", category);

        return ApiResponse.success(data);
    }

    @GetMapping("/type/{mailType}")
    public ApiResponse<Map<String, Object>> getMailsByType(
            @PathVariable String mailType,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        Page<MailRecord> pageResult = searchService.getMailsByType(mailType, page, size);

        Map<String, Object> data = new HashMap<>();
        data.put("mails", pageResult.getContent());
        data.put("total", pageResult.getTotalElements());
        data.put("totalPages", pageResult.getTotalPages());
        data.put("mailType", mailType);

        return ApiResponse.success(data);
    }
}
