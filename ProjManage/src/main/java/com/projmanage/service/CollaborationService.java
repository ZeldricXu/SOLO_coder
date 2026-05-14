package com.projmanage.service;

import com.projmanage.config.Constants;
import com.projmanage.model.Discussion;
import com.projmanage.model.Notification;
import com.projmanage.repository.DiscussionRepository;
import com.projmanage.repository.NotificationRepository;
import com.projmanage.util.IdGenerator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class CollaborationService {

    private final DiscussionRepository discussionRepository;
    private final NotificationRepository notificationRepository;

    public CollaborationService(DiscussionRepository discussionRepository,
                                 NotificationRepository notificationRepository) {
        this.discussionRepository = discussionRepository;
        this.notificationRepository = notificationRepository;
    }

    @Transactional
    public Discussion addComment(String projectId, String taskId, String author, String content) {
        Discussion discussion = new Discussion();
        discussion.setDiscussionId(IdGenerator.generateDiscussionId());
        discussion.setProjectId(projectId);
        discussion.setTaskId(taskId);
        discussion.setDiscussionType(Constants.DISCUSSION_TYPE_COMMENT);
        discussion.setContent(content);
        discussion.setAuthor(author);
        discussion.setCreatedAt(LocalDateTime.now());

        return discussionRepository.save(discussion);
    }

    @Transactional
    public Discussion addNote(String projectId, String taskId, String author, String content) {
        Discussion discussion = new Discussion();
        discussion.setDiscussionId(IdGenerator.generateDiscussionId());
        discussion.setProjectId(projectId);
        discussion.setTaskId(taskId);
        discussion.setDiscussionType(Constants.DISCUSSION_TYPE_NOTE);
        discussion.setContent(content);
        discussion.setAuthor(author);
        discussion.setCreatedAt(LocalDateTime.now());

        return discussionRepository.save(discussion);
    }

    public List<Discussion> getDiscussionsByProject(String projectId) {
        return discussionRepository.findByProjectIdOrderByCreatedAtDesc(projectId);
    }

    public List<Discussion> getDiscussionsByTask(String taskId) {
        return discussionRepository.findByTaskId(taskId);
    }

    @Transactional
    public Notification sendNotification(String recipientId, String projectId, String taskId,
                                         String notificationType, String title, String content) {
        Notification notification = new Notification();
        notification.setNotificationId(IdGenerator.generateNotificationId());
        notification.setRecipientId(recipientId);
        notification.setProjectId(projectId);
        notification.setTaskId(taskId);
        notification.setNotificationType(notificationType);
        notification.setTitle(title);
        notification.setContent(content);
        notification.setRead(false);
        notification.setCreatedAt(LocalDateTime.now());

        return notificationRepository.save(notification);
    }

    public List<Notification> getNotificationsByRecipient(String recipientId) {
        return notificationRepository.findByRecipientId(recipientId);
    }

    public List<Notification> getUnreadNotifications(String recipientId) {
        return notificationRepository.findByRecipientIdAndIsRead(recipientId, false);
    }

    @Transactional
    public void markNotificationAsRead(String notificationId) {
        notificationRepository.findById(notificationId).ifPresent(notification -> {
            notification.setRead(true);
            notificationRepository.save(notification);
        });
    }

    @Transactional
    public void markAllNotificationsAsRead(String recipientId) {
        List<Notification> notifications = getUnreadNotifications(recipientId);
        for (Notification notification : notifications) {
            notification.setRead(true);
        }
        notificationRepository.saveAll(notifications);
    }
}
