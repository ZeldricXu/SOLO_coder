package com.datateam.loganalyzer.notification;

import com.datateam.loganalyzer.model.AlertEvent;
import com.datateam.loganalyzer.model.NotificationConfig;

import javax.mail.Authenticator;
import javax.mail.Message;
import javax.mail.MessagingException;
import javax.mail.PasswordAuthentication;
import javax.mail.Session;
import javax.mail.Transport;
import javax.mail.internet.InternetAddress;
import javax.mail.internet.MimeMessage;
import java.util.Properties;

public class EmailNotifier extends AbstractNotifier implements NotificationChannel {

    private Session mailSession;

    public EmailNotifier(NotificationConfig config) {
        super(config);
        initializeSession();
    }

    public EmailNotifier(NotificationConfig config, TemplateEngine templateEngine) {
        super(config, templateEngine);
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
    protected boolean doSend(AlertEvent alert) throws Exception {
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
    }

    @Override
    public String getName() {
        return config.getName() != null ? config.getName() : "email";
    }
}
