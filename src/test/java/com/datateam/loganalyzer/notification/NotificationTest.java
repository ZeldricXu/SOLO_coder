package com.datateam.loganalyzer.notification;

import com.datateam.loganalyzer.model.AlertEvent;
import com.datateam.loganalyzer.model.AlertSeverity;
import com.datateam.loganalyzer.model.NotificationConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@DisplayName("通知推送器单元测试")
class NotificationTest {

    private AlertEvent testAlert;
    private AlertTemplateEngine templateEngine;

    @BeforeEach
    void setUp() {
        testAlert = createTestAlert();
        templateEngine = new AlertTemplateEngine();
    }

    @Test
    @DisplayName("正常路径：通知模板正确渲染")
    void testTemplateRendering() {
        String rendered = templateEngine.render(testAlert);

        assertThat(rendered).isNotEmpty();
        assertThat(rendered).contains("🚨 告警通知");
        assertThat(rendered).contains("High Error Rate");
        assertThat(rendered).contains("CRITICAL");
        assertThat(rendered).contains("Error count exceeded threshold");
    }

    @Test
    @DisplayName("正常路径：邮件主题正确生成")
    void testSubjectGeneration() {
        String subject = templateEngine.renderSubject(testAlert);

        assertThat(subject).isNotEmpty();
        assertThat(subject).contains("[CRITICAL]");
        assertThat(subject).contains("High Error Rate");
    }

    @Test
    @DisplayName("正常路径：纯文本格式正确")
    void testPlainTextRendering() {
        String plainText = templateEngine.renderPlainText(testAlert);

        assertThat(plainText).isNotEmpty();
        assertThat(plainText).contains("[ALERT]");
        assertThat(plainText).contains("Severity: CRITICAL");
    }

    @Test
    @DisplayName("正常路径：企业微信Markdown格式正确")
    void testWeChatWorkMarkdown() {
        String markdown = templateEngine.getWeChatWorkMarkdown(testAlert);

        assertThat(markdown).isNotEmpty();
        assertThat(markdown).contains("## 🚨 告警通知");
    }

    @Test
    @DisplayName("正常路径：Slack Markdown格式正确")
    void testSlackMarkdown() {
        String slackMd = templateEngine.getSlackMarkdown(testAlert);

        assertThat(slackMd).isNotEmpty();
    }

    @Test
    @DisplayName("边界场景：null告警正确处理")
    void testNullAlertHandling() {
        String rendered = templateEngine.render(null);
        assertThat(rendered).isEmpty();

        String subject = templateEngine.renderSubject(testAlert);
        assertThat(subject).isNotNull();
    }

    @Test
    @DisplayName("正常路径：通知管理器多通道管理")
    void testNotificationManagerMultiChannel() {
        NotificationManager manager = new NotificationManager();

        NotificationChannel mockEmail = mock(NotificationChannel.class);
        when(mockEmail.getName()).thenReturn("email");
        when(mockEmail.isEnabled()).thenReturn(true);
        when(mockEmail.getType()).thenReturn(NotificationConfig.ChannelType.EMAIL);
        when(mockEmail.send(any())).thenReturn(true);

        NotificationChannel mockWeChat = mock(NotificationChannel.class);
        when(mockWeChat.getName()).thenReturn("wechat");
        when(mockWeChat.isEnabled()).thenReturn(true);
        when(mockWeChat.getType()).thenReturn(NotificationConfig.ChannelType.WECHAT_WORK);
        when(mockWeChat.send(any())).thenReturn(false);

        manager.addChannel(mockEmail);
        manager.addChannel(mockWeChat);

        assertThat(manager.hasChannel("email")).isTrue();
        assertThat(manager.hasChannel("wechat")).isTrue();
        assertThat(manager.getChannels()).hasSize(2);

        boolean result = manager.sendNotification(testAlert);
        assertThat(result).isTrue();

        verify(mockEmail, times(1)).send(testAlert);
        verify(mockWeChat, times(1)).send(testAlert);
    }

    @Test
    @DisplayName("边界场景：目标服务不可达时的降级处理")
    void testServiceUnreachableDegradation() {
        NotificationManager manager = new NotificationManager();

        NotificationChannel failingChannel = mock(NotificationChannel.class);
        when(failingChannel.getName()).thenReturn("failing-channel");
        when(failingChannel.isEnabled()).thenReturn(true);
        when(failingChannel.getType()).thenReturn(NotificationConfig.ChannelType.WECHAT_WORK);
        when(failingChannel.send(any())).thenReturn(false);

        NotificationChannel workingChannel = mock(NotificationChannel.class);
        when(workingChannel.getName()).thenReturn("working-channel");
        when(workingChannel.isEnabled()).thenReturn(true);
        when(workingChannel.getType()).thenReturn(NotificationConfig.ChannelType.WECHAT_WORK);
        when(workingChannel.send(any())).thenReturn(true);

        manager.addChannel(failingChannel);
        manager.addChannel(workingChannel);

        boolean result = manager.sendNotification(testAlert);
        assertThat(result).isTrue();

        verify(failingChannel, times(1)).send(testAlert);
        verify(workingChannel, times(1)).send(testAlert);
    }

    @Test
    @DisplayName("边界场景：通道发送异常时不影响其他通道")
    void testChannelExceptionDoesNotAffectOthers() {
        NotificationManager manager = new NotificationManager();

        NotificationChannel exceptionChannel = mock(NotificationChannel.class);
        when(exceptionChannel.getName()).thenReturn("exception-channel");
        when(exceptionChannel.isEnabled()).thenReturn(true);
        when(exceptionChannel.getType()).thenReturn(NotificationConfig.ChannelType.EMAIL);
        when(exceptionChannel.send(any())).thenThrow(new RuntimeException("Connection failed"));

        NotificationChannel workingChannel = mock(NotificationChannel.class);
        when(workingChannel.getName()).thenReturn("working-channel");
        when(workingChannel.isEnabled()).thenReturn(true);
        when(workingChannel.getType()).thenReturn(NotificationConfig.ChannelType.SLACK);
        when(workingChannel.send(any())).thenReturn(true);

        manager.addChannel(exceptionChannel);
        manager.addChannel(workingChannel);

        boolean result = manager.sendNotification(testAlert);
        assertThat(result).isTrue();

        verify(exceptionChannel, times(1)).send(testAlert);
        verify(workingChannel, times(1)).send(testAlert);
    }

    @Test
    @DisplayName("边界场景：禁用的通道不发送")
    void testDisabledChannelNotUsed() {
        NotificationManager manager = new NotificationManager();

        NotificationChannel disabledChannel = mock(NotificationChannel.class);
        when(disabledChannel.getName()).thenReturn("disabled");
        when(disabledChannel.isEnabled()).thenReturn(false);

        NotificationChannel enabledChannel = mock(NotificationChannel.class);
        when(enabledChannel.getName()).thenReturn("enabled");
        when(enabledChannel.isEnabled()).thenReturn(true);
        when(enabledChannel.getType()).thenReturn(NotificationConfig.ChannelType.EMAIL);
        when(enabledChannel.send(any())).thenReturn(true);

        manager.addChannel(disabledChannel);
        manager.addChannel(enabledChannel);

        manager.sendNotification(testAlert);

        verify(disabledChannel, never()).send(any());
        verify(enabledChannel, times(1)).send(testAlert);
    }

    @Test
    @DisplayName("边界场景：指定通道发送")
    void testSpecificChannelSending() {
        NotificationManager manager = new NotificationManager();

        NotificationChannel emailChannel = mock(NotificationChannel.class);
        when(emailChannel.getName()).thenReturn("email");
        when(emailChannel.isEnabled()).thenReturn(true);
        when(emailChannel.getType()).thenReturn(NotificationConfig.ChannelType.EMAIL);
        when(emailChannel.send(any())).thenReturn(true);

        NotificationChannel slackChannel = mock(NotificationChannel.class);
        when(slackChannel.getName()).thenReturn("slack");
        when(slackChannel.isEnabled()).thenReturn(true);
        when(slackChannel.getType()).thenReturn(NotificationConfig.ChannelType.SLACK);
        when(slackChannel.send(any())).thenReturn(true);

        manager.addChannel(emailChannel);
        manager.addChannel(slackChannel);

        manager.sendNotification(testAlert, List.of("email"));

        verify(emailChannel, times(1)).send(testAlert);
        verify(slackChannel, never()).send(any());
    }

    @Test
    @DisplayName("边界场景：不存在的通道被忽略")
    void testNonExistentChannelIgnored() {
        NotificationManager manager = new NotificationManager();

        NotificationChannel emailChannel = mock(NotificationChannel.class);
        when(emailChannel.getName()).thenReturn("email");
        when(emailChannel.isEnabled()).thenReturn(true);
        when(emailChannel.getType()).thenReturn(NotificationConfig.ChannelType.EMAIL);
        when(emailChannel.send(any())).thenReturn(true);

        manager.addChannel(emailChannel);

        boolean result = manager.sendNotification(testAlert, List.of("nonexistent", "email"));
        assertThat(result).isTrue();

        verify(emailChannel, times(1)).send(testAlert);
    }

    @Test
    @DisplayName("边界场景：null告警不发送")
    void testNullAlertNotSent() {
        NotificationManager manager = new NotificationManager();
        boolean result = manager.sendNotification(null);
        assertThat(result).isFalse();
    }

    private AlertEvent createTestAlert() {
        AlertEvent alert = new AlertEvent();
        alert.setRuleId("rule-001");
        alert.setRuleName("High Error Rate");
        alert.setSeverity(AlertSeverity.CRITICAL);
        alert.setDescription("Error count exceeded threshold of 10");
        alert.setTriggeredAt(Instant.now().minusSeconds(300));
        alert.setActive(true);
        alert.addDetail("Metric: errors");
        alert.addDetail("Threshold: > 10");
        alert.addDetail("Observed: 15");
        return alert;
    }
}
