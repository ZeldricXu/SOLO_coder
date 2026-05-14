package com.example.mailservice.service;

import com.example.mailservice.config.AppConfig;
import com.example.mailservice.model.MailAttachment;
import com.example.mailservice.repository.MailAttachmentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class AttachmentService {

    private final MailAttachmentRepository attachmentRepository;
    private final AppConfig appConfig;

    @Transactional
    public String saveAttachment(String mailId, String fileName, String contentType, byte[] content) {
        try {
            String basePath = appConfig.getMail().getAttachmentPath();
            File directory = new File(basePath + File.separator + mailId);
            if (!directory.exists()) {
                directory.mkdirs();
            }

            String uniqueFileName = System.currentTimeMillis() + "_" + fileName.replaceAll("[^a-zA-Z0-9._-]", "_");
            String filePath = directory.getAbsolutePath() + File.separator + uniqueFileName;

            try (FileOutputStream fos = new FileOutputStream(filePath)) {
                fos.write(content);
            }

            MailAttachment attachment = MailAttachment.builder()
                    .attachmentId("att_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12))
                    .mailId(mailId)
                    .fileName(fileName)
                    .filePath(filePath)
                    .fileSize((long) content.length)
                    .contentType(contentType)
                    .build();

            attachmentRepository.save(attachment);
            log.info("附件保存成功，mailId: {}, fileName: {}", mailId, fileName);
            return fileName;
        } catch (IOException e) {
            log.error("附件保存失败: {}", e.getMessage(), e);
            throw new RuntimeException("附件保存失败: " + e.getMessage());
        }
    }

    public List<MailAttachment> getAttachmentsByMailId(String mailId) {
        return attachmentRepository.findByMailId(mailId);
    }

    public Optional<MailAttachment> getAttachmentById(String attachmentId) {
        return attachmentRepository.findByAttachmentId(attachmentId);
    }

    @Transactional
    public void deleteAttachment(String attachmentId) {
        Optional<MailAttachment> attachmentOpt = attachmentRepository.findByAttachmentId(attachmentId);
        if (attachmentOpt.isPresent()) {
            MailAttachment attachment = attachmentOpt.get();
            File file = new File(attachment.getFilePath());
            if (file.exists()) {
                file.delete();
            }
            attachmentRepository.delete(attachment);
        }
    }
}
