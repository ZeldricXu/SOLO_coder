package com.example.mailservice.service;

import com.example.mailservice.config.AppConfig;
import com.example.mailservice.dto.MailSearchRequest;
import com.example.mailservice.model.MailRecord;
import com.example.mailservice.repository.MailRecordRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class SearchService {

    private final MailRecordRepository mailRecordRepository;
    private final IndexWorker indexWorker;
    private final AppConfig appConfig;

    public Page<MailRecord> searchMails(MailSearchRequest request) {
        int page = request.getPage() != null ? request.getPage() : 0;
        int size = request.getSize() != null ? request.getSize() : (appConfig.getSearch() != null ? appConfig.getSearch().getPageSize() : 20);
        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "sentAt"));

        log.info("搜索邮件，keyword: {}, category: {}, page: {}, size: {}",
                request.getKeyword(), request.getCategory(), page, size);

        return mailRecordRepository.searchMails(
                request.getKeyword(),
                request.getCategory(),
                request.getSender(),
                request.getMailType(),
                request.getMailStatus(),
                request.getStartTime(),
                request.getEndTime(),
                pageable
        );
    }

    public Page<MailRecord> searchByKeyword(String keyword, int page, int size) {
        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "sentAt"));
        return mailRecordRepository.searchByKeyword(keyword, pageable);
    }

    public Optional<MailRecord> getMailByMailId(String mailId) {
        return mailRecordRepository.findByMailId(mailId);
    }

    public Page<MailRecord> getMailsByCategory(String category, int page, int size) {
        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "sentAt"));
        return mailRecordRepository.findByCategory(category, pageable);
    }

    public Page<MailRecord> getMailsByType(String mailType, int page, int size) {
        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "sentAt"));
        return mailRecordRepository.findByMailType(mailType, pageable);
    }

    public Page<MailRecord> getMailsBySender(String sender, int page, int size) {
        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "sentAt"));
        return mailRecordRepository.findBySender(sender, pageable);
    }

    @Transactional
    public void submitIndexTask(MailRecord record) {
        if (appConfig.getSearch() != null && appConfig.getSearch().isAsyncIndexing()) {
            indexWorker.submitTask(record);
            log.info("索引任务已提交，mailId: {}", record.getMailId());
        } else {
            mailRecordRepository.save(record);
            log.info("同步更新索引，mailId: {}", record.getMailId());
        }
    }

    public void submitIndexTask(String mailId) {
        mailRecordRepository.findByMailId(mailId).ifPresent(this::submitIndexTask);
    }
}
