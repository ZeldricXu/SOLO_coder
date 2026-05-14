package com.example.mailservice.service;

import com.example.mailservice.config.AppConfig;
import com.example.mailservice.model.MailRecord;
import com.example.mailservice.repository.MailRecordRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.mail.*;
import javax.mail.internet.InternetAddress;
import javax.mail.internet.MimeMessage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class MailReceiveService {

    private final MailRecordRepository mailRecordRepository;
    private final ArchiveService archiveService;
    private final AttachmentService attachmentService;
    private final HistoryService historyService;
    private final AnalysisService analysisService;
    private final AppConfig appConfig;

    @Value("${app.mail.imap.host}")
    private String imapHost;

    @Value("${app.mail.imap.port}")
    private int imapPort;

    @Value("${app.mail.imap.username}")
    private String username;

    @Value("${app.mail.imap.password}")
    private String password;

    @Value("${app.mail.imap.protocol}")
    private String protocol;

    @Scheduled(fixedRate = 60000)
    @Transactional
    public void pollAndReceiveMails() {
        log.info("开始轮询接收邮件...");
        receiveMails();
    }

    public List<MailRecord> receiveMails() {
        List<MailRecord> receivedMails = new ArrayList<>();
        Store store = null;
        Folder folder = null;

        try {
            Properties props = new Properties();
            props.put("mail.store.protocol", protocol);
            props.put("mail.imaps.host", imapHost);
            props.put("mail.imaps.port", imapPort);
            props.put("mail.imaps.ssl.enable", "true");
            props.put("mail.imaps.auth", "true");

            Session session = Session.getInstance(props);
            store = session.getStore(protocol);
            store.connect(imapHost, imapPort, username, password);

            folder = store.getFolder("INBOX");
            folder.open(Folder.READ_WRITE);

            Message[] messages = folder.getMessages();
            log.info("发现 {} 封待处理邮件", messages.length);

            for (Message message : messages) {
                if (!message.getFlags().contains(Flags.Flag.SEEN)) {
                    try {
                        MailRecord mailRecord = processIncomingMail(message);
                        receivedMails.add(mailRecord);
                        message.setFlag(Flags.Flag.SEEN, true);
                    } catch (Exception e) {
                        log.error("处理邮件失败: {}", e.getMessage(), e);
                    }
                }
            }

        } catch (Exception e) {
            log.error("接收邮件失败: {}", e.getMessage(), e);
        } finally {
            try {
                if (folder != null) folder.close(false);
                if (store != null) store.close();
            } catch (Exception e) {
                log.warn("关闭邮件连接时出错: {}", e.getMessage());
            }
        }

        return receivedMails;
    }

    private MailRecord processIncomingMail(Message message) throws Exception {
        String mailId = "mail_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);

        String subject = message.getSubject();
        String sender = getSender(message);
        String recipients = getRecipients(message);
        String content = getMailContent(message);
        List<String> attachments = extractAttachments(message, mailId);

        MailRecord mailRecord = MailRecord.builder()
                .mailId(mailId)
                .mailType("inbound")
                .sender(sender)
                .recipients(recipients)
                .subject(subject)
                .content(content)
                .attachments(attachments != null && !attachments.isEmpty() ? String.join(",", attachments) : null)
                .mailStatus("received")
                .sentAt(message.getSentDate() != null ?
                        message.getSentDate().toInstant().atZone(ZoneId.systemDefault()).toLocalDateTime() : LocalDateTime.now())
                .build();

        mailRecord = mailRecordRepository.save(mailRecord);

        archiveService.archiveInboundMail(mailId, subject, content);

        historyService.recordHistory(mailId, "RECEIVED", "邮件接收成功", "system");
        analysisService.incrementReceivedCount();

        log.info("邮件接收并归档成功，mailId: {}", mailId);
        return mailRecord;
    }

    private String getSender(Message message) throws MessagingException {
        Address[] fromAddresses = message.getFrom();
        if (fromAddresses != null && fromAddresses.length > 0) {
            if (fromAddresses[0] instanceof InternetAddress) {
                return ((InternetAddress) fromAddresses[0]).getAddress();
            }
            return fromAddresses[0].toString();
        }
        return "unknown";
    }

    private String getRecipients(Message message) throws MessagingException {
        List<String> recipientList = new ArrayList<>();
        Address[] toAddresses = message.getRecipients(Message.RecipientType.TO);
        if (toAddresses != null) {
            for (Address addr : toAddresses) {
                if (addr instanceof InternetAddress) {
                    recipientList.add(((InternetAddress) addr).getAddress());
                } else {
                    recipientList.add(addr.toString());
                }
            }
        }
        return String.join(",", recipientList);
    }

    private String getMailContent(Message message) throws MessagingException, IOException {
        if (message.isMimeType("text/plain")) {
            return (String) message.getContent();
        } else if (message.isMimeType("text/html")) {
            return (String) message.getContent();
        } else if (message.isMimeType("multipart/*")) {
            Multipart multipart = (Multipart) message.getContent();
            StringBuilder content = new StringBuilder();
            for (int i = 0; i < multipart.getCount(); i++) {
                BodyPart bodyPart = multipart.getBodyPart(i);
                if (!Part.ATTACHMENT.equalsIgnoreCase(bodyPart.getDisposition())) {
                    if (bodyPart.isMimeType("text/plain") || bodyPart.isMimeType("text/html")) {
                        content.append(bodyPart.getContent().toString());
                    }
                }
            }
            return content.toString();
        }
        return "";
    }

    private List<String> extractAttachments(Message message, String mailId) throws Exception {
        List<String> attachmentNames = new ArrayList<>();

        if (message.isMimeType("multipart/*")) {
            Multipart multipart = (Multipart) message.getContent();
            for (int i = 0; i < multipart.getCount(); i++) {
                BodyPart bodyPart = multipart.getBodyPart(i);
                if (Part.ATTACHMENT.equalsIgnoreCase(bodyPart.getDisposition())) {
                    String fileName = bodyPart.getFileName();
                    if (fileName != null) {
                        ByteArrayOutputStream baos = new ByteArrayOutputStream();
                        bodyPart.getInputStream().transferTo(baos);
                        byte[] content = baos.toByteArray();

                        String savedFileName = attachmentService.saveAttachment(mailId, fileName,
                                bodyPart.getContentType(), content);
                        attachmentNames.add(savedFileName);
                    }
                }
            }
        }

        return attachmentNames;
    }
}
