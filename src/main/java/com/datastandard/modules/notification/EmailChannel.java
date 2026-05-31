package com.datastandard.modules.notification;

import cn.hutool.core.util.StrUtil;
import cn.hutool.core.util.ReUtil;
import com.datastandard.modules.notification.dto.NotificationRequest;
import com.datastandard.modules.notification.dto.NotificationResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.Properties;
import java.util.regex.Pattern;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;

@Slf4j
@Component
@RequiredArgsConstructor
public class EmailChannel implements NotificationChannel {

    private static final Pattern EMAIL_PATTERN = Pattern.compile(
            "^[A-Za-z0-9+_.-]+@[A-Za-z0-9.-]+$");

    @Value("${notification.email.smtp.host:localhost}")
    private String smtpHost;

    @Value("${notification.email.smtp.port:25}")
    private int smtpPort;

    @Value("${notification.email.smtp.username:}")
    private String username;

    @Value("${notification.email.smtp.password:}")
    private String password;

    @Value("${notification.email.from:noreply@example.com}")
    private String fromEmail;

    @Value("${notification.email.enabled:true}")
    private boolean enabled;

    @Override
    public String getChannelName() {
        return "EMAIL";
    }

    @Override
    public int getPriority() {
        return 10;
    }

    @Override
    public boolean isAvailable() {
        return enabled && StrUtil.isNotBlank(smtpHost);
    }

    @Override
    public boolean supports(NotificationRequest request) {
        if (!isAvailable()) {
            return false;
        }
        return request.getRecipients().stream()
                .allMatch(this::isValidEmail);
    }

    @Override
    public Mono<NotificationResult> send(NotificationRequest request, String recipient) {
        return Mono.fromCallable(() -> {
            long startTime = System.nanoTime();
            try {
                Properties props = new Properties();
                props.put("mail.smtp.host", smtpHost);
                props.put("mail.smtp.port", smtpPort);
                props.put("mail.smtp.auth", StrUtil.isNotBlank(username));
                props.put("mail.smtp.starttls.enable", "true");

                Session session = Session.getInstance(props);
                MimeMessage message = new MimeMessage(session);
                message.setFrom(new InternetAddress(fromEmail));
                message.setRecipients(MimeMessage.RecipientType.TO, InternetAddress.parse(recipient));
                message.setSubject(request.getSubject());
                message.setText(request.getContent());
                message.setHeader("X-Trace-Id", request.getTraceId());

                if (StrUtil.isNotBlank(username)) {
                    try (Transport transport = session.getTransport("smtp")) {
                        transport.connect(smtpHost, smtpPort, username, password);
                        transport.sendMessage(message, message.getAllRecipients());
                    }
                } else {
                    Transport.send(message);
                }

                long duration = (System.nanoTime() - startTime) / 1_000_000;
                log.debug("Email sent successfully to {}", recipient);
                return NotificationResult.success(getChannelName(), recipient, duration);
            } catch (Exception e) {
                long duration = (System.nanoTime() - startTime) / 1_000_000;
                log.error("Failed to send email to {}: {}", recipient, e.getMessage(), e);
                return NotificationResult.failure(getChannelName(), recipient,
                        e.getMessage(), request.getRetryCount());
            }
        }).subscribeOn(Schedulers.boundedElastic());
    }

    private boolean isValidEmail(String email) {
        return StrUtil.isNotBlank(email) && ReUtil.isMatch(EMAIL_PATTERN, email);
    }
}
