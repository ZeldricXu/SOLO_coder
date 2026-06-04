package com.cicd.server.notification.channel;

import com.cicd.common.enums.NotificationChannel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;
import jakarta.mail.internet.MimeMessage;
import java.util.Map;

@Slf4j
@Component
public class EmailSender implements NotificationSender {

    @Autowired(required = false)
    private JavaMailSender mailSender;

    @Value("${notification.email.from:}")
    private String defaultFrom;

    @Override
    public NotificationChannel getChannelType() {
        return NotificationChannel.EMAIL;
    }

    @Override
    public boolean send(String target, String title, String content, Map<String, Object> extra) throws Exception {
        if (mailSender == null) {
            log.warn("JavaMailSender not configured, skipping email notification");
            return false;
        }

        if (target == null || target.isEmpty()) {
            log.warn("Email target not specified");
            return false;
        }

        String from = extra != null && extra.containsKey("from") ? (String) extra.get("from") : defaultFrom;
        if (from == null || from.isEmpty()) {
            log.warn("Email from address not configured");
            return false;
        }

        boolean isHtml = extra != null && (Boolean) extra.getOrDefault("html", false);

        MimeMessage message = mailSender.createMimeMessage();
        MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");

        helper.setFrom(from);
        helper.setTo(target.split(","));
        helper.setSubject(title);
        helper.setText(content, isHtml);

        if (extra != null && extra.containsKey("cc")) {
            helper.setCc(((String) extra.get("cc")).split(","));
        }
        if (extra != null && extra.containsKey("bcc")) {
            helper.setBcc(((String) extra.get("bcc")).split(","));
        }

        mailSender.send(message);
        log.info("Email notification sent successfully to {}", target);
        return true;
    }
}
