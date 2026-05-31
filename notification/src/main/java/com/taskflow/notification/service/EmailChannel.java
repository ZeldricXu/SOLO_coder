package com.taskflow.notification.service;

import com.taskflow.notification.model.NotificationRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class EmailChannel implements NotificationChannel {

    private final JavaMailSender mailSender;

    @Override
    public String getChannelName() {
        return "email";
    }

    @Override
    public boolean send(NotificationRequest request, String content, String subject) throws Exception {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setTo(request.getReceivers().toArray(new String[0]));
        message.setSubject(subject != null ? subject : "通知");
        message.setText(content);
        if (request.getSender() != null) {
            message.setFrom(request.getSender());
        }

        mailSender.send(message);
        log.info("Email sent to: {}", request.getReceivers());
        return true;
    }
}
