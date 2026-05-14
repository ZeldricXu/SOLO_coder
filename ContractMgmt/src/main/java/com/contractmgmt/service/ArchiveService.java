package com.contractmgmt.service;

import cn.hutool.core.util.IdUtil;
import com.contractmgmt.entity.ArchiveRecord;
import com.contractmgmt.entity.Contract;
import com.contractmgmt.entity.ContractHistory;
import com.contractmgmt.exception.ContractException;
import com.contractmgmt.repository.ArchiveRecordRepository;
import com.contractmgmt.repository.ContractRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Service
public class ArchiveService {

    private static final Logger logger = LoggerFactory.getLogger(ArchiveService.class);

    private final ArchiveRecordRepository archiveRecordRepository;
    private final ContractRepository contractRepository;
    private final StatisticsService statisticsService;
    private final HistoryService historyService;
    private final ObjectMapper objectMapper;

    public ArchiveService(
            ArchiveRecordRepository archiveRecordRepository,
            ContractRepository contractRepository,
            StatisticsService statisticsService,
            HistoryService historyService,
            ObjectMapper objectMapper) {
        this.archiveRecordRepository = archiveRecordRepository;
        this.contractRepository = contractRepository;
        this.statisticsService = statisticsService;
        this.historyService = historyService;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public ArchiveRecord archiveContract(String contractId, String operator, String reason) {
        Contract contract = contractRepository.findByContractId(contractId)
                .orElseThrow(() -> new ContractException(404, "合同不存在: " + contractId));

        Optional<ArchiveRecord> existing = archiveRecordRepository.findByContractId(contractId);
        if (existing.isPresent()) {
            throw new ContractException(400, "合同已归档: " + contractId);
        }

        String contractSnapshot;
        try {
            contractSnapshot = objectMapper.writeValueAsString(contract);
        } catch (JsonProcessingException e) {
            throw new ContractException(500, "合同快照生成失败");
        }

        ArchiveRecord archive = new ArchiveRecord();
        archive.setArchiveId("archive_" + IdUtil.getSnowflakeNextIdStr());
        archive.setContractId(contractId);
        archive.setArchiveLocation("/contracts/archive/" + contractId);
        archive.setArchiveReason(reason != null ? reason : "合同到期自动归档");
        archive.setArchiveOperator(operator != null ? operator : "system");
        archive.setArchiveTime(LocalDateTime.now());
        archive.setContractSnapshot(contractSnapshot);
        archiveRecordRepository.save(archive);

        String oldStatus = contract.getContractStatus();
        contract.setContractStatus("archived");
        contract.setArchiveTime(LocalDateTime.now());
        contract.setUpdatedAt(LocalDateTime.now());
        contractRepository.save(contract);

        statisticsService.decrementActiveCount();
        statisticsService.subtractActiveAmount(contract.getContractAmount());
        statisticsService.incrementArchivedCount();

        historyService.recordHistory(contractId, "archive", "archive",
                operator != null ? operator : "system",
                "合同归档: " + contract.getContractName(), oldStatus, "archived");

        logger.info("合同归档完成: {}", contractId);
        return archive;
    }

    public Optional<ArchiveRecord> getArchiveByContract(String contractId) {
        return archiveRecordRepository.findByContractId(contractId);
    }

    public List<ArchiveRecord> searchArchives(String keyword) {
        if (keyword == null || keyword.trim().isEmpty()) {
            return archiveRecordRepository.findAll();
        }
        return archiveRecordRepository.searchByKeyword(keyword);
    }

    public ArchiveRecord getArchive(String archiveId) {
        return archiveRecordRepository.findByArchiveId(archiveId)
                .orElseThrow(() -> new ContractException(404, "归档记录不存在: " + archiveId));
    }
}
