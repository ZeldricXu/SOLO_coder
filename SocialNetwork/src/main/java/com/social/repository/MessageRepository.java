package com.social.repository;

import com.social.entity.Message;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface MessageRepository extends JpaRepository<Message, Long> {
    Optional<Message> findByMessageId(String messageId);
    List<Message> findByFromUserOrToUserOrderBySentAtDesc(String fromUser, String toUser);
    List<Message> findByFromUserAndToUserOrderBySentAtDesc(String fromUser, String toUser);
    List<Message> findByToUserAndMessageStatus(String toUser, String status);
    
    List<Message> findByToUserAndMessageStatusInOrderBySentAtDesc(String toUser, List<String> statuses);
    List<Message> findByNeedsConfirmationTrueAndConfirmedFalseAndMessageStatusInOrderBySentAtAsc(
            List<String> statuses);
    List<Message> findByToUserAndNeedsConfirmationTrueAndConfirmedFalseOrderBySentAtDesc(String toUser);
    
    long countByNeedsConfirmationTrueAndConfirmedFalse();
    long count();
}
