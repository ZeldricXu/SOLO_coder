package com.example.mailservice.service;

import com.example.mailservice.config.AppConfig;
import com.example.mailservice.dto.MailSendRequest;
import com.example.mailservice.model.MailRecord;
import com.example.mailservice.model.SendStatus;
import com.example.mailservice.repository.MailRecordRepository;
import com.example.mailservice.repository.SendStatusRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.validator.routines.EmailValidator;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.mail.MessagingException;
import javax.mail.internet.MimeMessage;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class MailSendService {

    private final JavaMailSender mailSender;
    private final MailRecordRepository mailRecordRepository;
    private final SendStatusRepository sendStatusRepository;
    private final ArchiveService archiveService;
    private final AttachmentService attachmentService;
    private final StatusService statusService;
    private final HistoryService historyService;
    private final AnalysisService analysisService;
    private final AppConfig appConfig;
    private final MailSendWorker mailSendWorker;

    @Value("${spring.mail.username}")
    private String senderEmail;

    @Transactional
    public SendResult sendMail(MailSendRequest request) {
        log.info("开始处理邮件发送请求，收件人数量: {}", request.getRecipients().size());

        List<String> invalidEmails = validateRecipients(request.getRecipients());
        if (!invalidEmails.isEmpty()) {
            return SendResult.builder()
                    .success(false)
                    .message("无效的收件人地址: " + String.join(", ", invalidEmails))
                    .build();
        }

        if (request.getAttachments() != null && !request.getAttachments().isEmpty()) {
            long totalSize = request.getAttachments().stream()
                    .mapToLong(a -> a.getContent() != null ? a.getContent().length : 0)
                    .sum();
            if (totalSize > appConfig.getMail().getMaxAttachmentSize()) {
                return SendResult.builder()
                        .success(false)
                        .message("附件总大小超出限制，最大允许: " + appConfig.getMail().getMaxAttachmentSize() + " bytes")
                        .build();
            }
        }

        String mailId = "mail_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);

        MailRecord mailRecord = createMailRecord(mailId, request);
        mailRecord.setMailStatus("queued");
        mailRecord = mailRecordRepository.save(mailRecord);

        mailSendWorker.submitTask(request, mailRecord);

        log.info("邮件已入队，mailId: {}", mailId);

        return SendResult.builder()
                .success(true)
                .mailId(mailId)
                .status("queued")
                .message("邮件已加入发送队列，请稍后查看发送状态")
                .build();
    }

    @Async
    public void sendMailAsync(MailSendRequest request) {
        sendMail(request);
    }

    @Transactional
    public SendResult sendMailSync(MailSendRequest request) {
        log.info("开始处理同步邮件发送请求，收件人数量: {}", request.getRecipients().size());

        List<String> invalidEmails = validateRecipients(request.getRecipients());
        if (!invalidEmails.isEmpty()) {
            return SendResult.builder()
                    .success(false)
                    .message("无效的收件人地址: " + String.join(", ", invalidEmails))
                    .build();
        }

        String mailId = "mail_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);

        MailRecord mailRecord = createMailRecord(mailId, request);
        mailRecord = mailRecordRepository.save(mailRecord);

        return executeSend(mailRecord, request);
    }

    private SendResult executeSend(MailRecord mailRecord, MailSendRequest request) {
        String mailId = mailRecord.getMailId();
        String smtpResponse = null;
        boolean success = false;
        String errorMessage = null;

        try {
            MimeMessage message = buildMimeMessage(request);
            mailSender.send(message);
            smtpResponse = "250 OK";
            success = true;
            log.info("邮件发送成功，mailId: {}", mailId);
        } catch (Exception e) {
            errorMessage = e.getMessage();
            log.error("邮件发送失败，mailId: {}, 错误: {}", mailId, errorMessage);
        }

        mailRecord.setMailStatus(success ? "sent" : "failed");
        if (success) {
            mailRecord.setSentAt(LocalDateTime.now());
        }
        mailRecordRepository.save(mailRecord);

        statusService.createStatus(mailId, success ? "success" : "failed", smtpResponse, errorMessage);

        if (success) {
            archiveService.archiveMail(mailId, request.getCategory());
            historyService.recordHistory(mailId, "SENT", "邮件发送成功", "system");
            analysisService.incrementSentCount();
        } else {
            historyService.recordHistory(mailId, "SEND_FAILED", "邮件发送失败: " + errorMessage, "system");
            analysisService.incrementFailedCount();
        }

        return SendResult.builder()
                .success(success)
                .mailId(mailId)
                .status(success ? "sent" : "failed")
                .message(success ? "邮件发送成功" : "邮件发送失败: " + errorMessage)
                .build();
    }

    private MimeMessage buildMimeMessage(MailSendRequest request) throws MessagingException {
        MimeMessage message = mailSender.createMimeMessage();
        MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");

        helper.setFrom(senderEmail);
        helper.setTo(request.getRecipients().toArray(new String[0]));

        if (request.getCc() != null && !request.getCc().isEmpty()) {
            helper.setCc(request.getCc().toArray(new String[0]));
        }
        if (request.getBcc() != null && !request.getBcc().isEmpty()) {
            helper.setBcc(request.getBcc().toArray(new String[0]));
        }

        helper.setSubject(request.getSubject());
        helper.setText(request.getContent() != null ? request.getContent() : "",
                "text/html".equalsIgnoreCase(request.getContentType()));

        if (request.getAttachments() != null && !request.getAttachments().isEmpty()) {
            for (MailSendRequest.AttachmentInfo att : request.getAttachments()) {
                if (att.getContent() != null && att.getContent().length > 0) {
                    helper.addAttachment(att.getFileName(),
                            new javax.activation.DataSource() {
                                @Override
                                public java.io.InputStream getInputStream() {
                                    return new java.io.ByteArrayInputStream(att.getContent());
                                }

                                @Override
                                public java.io.OutputStream getOutputStream() {
                                    throw new UnsupportedOperationException();
                                }

                                @Override
                                public String getContentType() {
                                    return att.getContentType() != null ? att.getContentType() : "application/octet-stream";
                                }

                                @Override
                                public String getName() {
                                    return att.getFileName();
                                }
                            });
                }
            }
        }

        return message;
    }

    private MailRecord createMailRecord(String mailId, MailSendRequest request) {
        return MailRecord.builder()
                .mailId(mailId)
                .mailType("outbound")
                .sender(senderEmail)
                .recipients(String.join(",", request.getRecipients()))
                .subject(request.getSubject())
                .content(request.getContent())
                .mailStatus("pending")
                .category(request.getCategory() != null ? request.getCategory() : "uncategorized")
                .build();
    }

    private List<String> validateRecipients(List<String> recipients) {
        List<String> invalidEmails = new ArrayList<>();
        EmailValidator validator = EmailValidator.getInstance();
        for (String email : recipients) {
            if (!validator.isValid(email)) {
                invalidEmails.add(email);
            }
        }
        return invalidEmails;
    }

    @lombok.Data
    @lombok.Builder
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class SendResult {
        private boolean success;
        private String mailId;
        private String status;
        private String message;
    }
}
