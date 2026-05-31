package com.solocoder.platform.notification.channel;

import com.solocoder.platform.notification.model.NotificationRequest;
import com.solocoder.platform.notification.model.NotificationResult;

public interface ChannelProvider {

    String getChannelType();

    NotificationResult send(NotificationRequest request, String renderedContent);

    boolean supports(String channelType);
}
