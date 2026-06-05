package com.datateam.loganalyzer.notification;

import com.datateam.loganalyzer.model.AlertEvent;
import com.datateam.loganalyzer.model.NotificationConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.mail.Authenticator;
import javax.mail.Message;
import javax.mail.MessagingException;
import javax.mail.PasswordAuthentication;
import javax.mail.Session;
import javax.mail.Transport;
import javax.mail.internet.InternetAddress;
import javax.mail.internet.MimeMessage;
import java.util.Properties;

public class EmailNotifier implements NotificationChannel {

    private static final Logger logger = LoggerFactory.getLogger(EmailNotifier.class);

    private final NotificationConfig config;
    private final AlertTemplateEngine templateEngine;
    private Session mailSession;

    public EmailNotifier(NotificationConfig config) {
        this.config = config;
        this.templateEngine = new AlertTemplateEngine();
        initializeSession();
    }

    private void initializeSession() {
        Properties props = new Properties();
        props.put("mail.smtp.host", config.getSmtpHost());
        props.put("mail.smtp.port", String.valueOf(config.getSmtpPort()));
        props.put("mail.smtp.auth", String.valueOf(config.isSmtpAuth()));
        props.put("mail.smtp.starttls.enable", String.valueOf(config.isSmtpStartTls()));
        props.put("mail.smtp.ssl.trust", config.getSmtpHost());

        if (config.isSmtpAuth()) {
            this.mailSession = Session.getInstance(props, new Authenticator() {
                @Override
                protected PasswordAuthentication getPasswordAuthentication() {
                    return new PasswordAuthentication(
                        config.getSmtpUser(),
                        config.getSmtpPassword()
                    );
                }
            });
        } else {
            this.mailSession = Session.getInstance(props);
        }
    }

    @Override
    public boolean send(AlertEvent alert) {
        if (!config.isEnabled()) {
            logger.warn("Email channel is disabled, skipping notification");
            return false;
        }

        try {
            Message message = new MimeMessage(mailSession);

            if (config.getFromAddress() != null) {
                message.setFrom(new InternetAddress(config.getFromAddress()));
            }

            String toAddresses = config.getToAddresses();
            if (toAddresses != null && !toAddresses.isEmpty()) {
                message.setRecipients(Message.RecipientType.TO,
                    InternetAddress.parse(toAddresses.replace(",", ";")));
            }

            message.setSubject(templateEngine.renderSubject(alert));

            String content = templateEngine.render(alert);
            message.setContent(content, "text/html; charset=utf-8");

            Transport.send(message);
            logger.info("Email notification sent successfully for alert: {}", alert.getRuleId());
            return true;

        } catch (MessagingException e) {
            logger.error("Failed to send email notification", e);
            return false;
        }
    }

    @Override
    public String getName() {
        return config.getName() != null ? config.getName() : "email";
    }

    @Override
    public NotificationConfig.ChannelType getType() {
        return NotificationConfig.ChannelType.EMAIL;
    }

    @Override
    public boolean isEnabled() {
        return config.isEnabled();
    }

    @Override
    public NotificationConfig getConfig() {
        return config;
    }
}
