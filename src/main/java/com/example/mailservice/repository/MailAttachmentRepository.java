package com.example.mailservice.repository;

import com.example.mailservice.model.MailAttachment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface MailAttachmentRepository extends JpaRepository<MailAttachment, Long> {
    Optional<MailAttachment> findByAttachmentId(String attachmentId);

    List<MailAttachment> findByMailId(String mailId);

    List<MailAttachment> findByMailIdIn(List<String> mailIds);
}
