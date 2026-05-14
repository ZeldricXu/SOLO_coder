package com.invoice.mgmt.archive.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.invoice.mgmt.common.entity.Invoice;
import com.invoice.mgmt.common.entity.InvoiceArchive;
import com.invoice.mgmt.common.enums.ArchiveTypeEnum;
import com.invoice.mgmt.common.exception.InvoiceException;
import com.invoice.mgmt.common.mapper.InvoiceMapper;
import com.invoice.mgmt.common.util.DateTimeUtil;
import com.invoice.mgmt.common.util.IdGenerator;
import com.invoice.mgmt.archive.mapper.InvoiceArchiveMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;

@Service
public class InvoiceArchiveService {
    private static final Logger logger = LoggerFactory.getLogger(InvoiceArchiveService.class);

    @Value("${invoice.archive.path:/tmp/invoice-archive}")
    private String archiveBasePath;

    @Autowired
    private InvoiceArchiveMapper archiveMapper;

    @Autowired
    private InvoiceMapper invoiceMapper;

    @Autowired
    private ObjectMapper objectMapper;

    @Transactional
    public InvoiceArchive archiveElectronic(String invoiceId, String operator) {
        Invoice invoice = invoiceMapper.findById(invoiceId);
        if (invoice == null) {
            throw InvoiceException.notFound();
        }
        try {
            String content = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(invoice);
            String fileName = generateFileName(invoiceId, "electronic");
            Path filePath = buildFilePath(ArchiveTypeEnum.ELECTRONIC, fileName);
            Files.createDirectories(filePath.getParent());
            Files.write(filePath, content.getBytes(StandardCharsets.UTF_8));

            long fileSize = Files.size(filePath);
            String md5 = calculateMD5(content);

            InvoiceArchive archive = InvoiceArchive.builder()
                    .archiveId(IdGenerator.generateArchiveId())
                    .invoiceId(invoiceId)
                    .archiveType(ArchiveTypeEnum.ELECTRONIC.getCode())
                    .storagePath(filePath.toString())
                    .fileName(fileName)
                    .fileSize(fileSize)
                    .md5(md5)
                    .archivedBy(operator != null ? operator : "system")
                    .archivedAt(DateTimeUtil.now())
                    .createdAt(DateTimeUtil.now())
                    .build();
            archiveMapper.insert(archive);
            logger.info("电子归档完成: invoiceId={}, file={}", invoiceId, filePath);
            return archive;
        } catch (IOException e) {
            logger.error("电子归档失败: invoiceId={}", invoiceId, e);
            throw new InvoiceException("归档失败: " + e.getMessage());
        }
    }

    @Transactional
    public InvoiceArchive archivePaper(String invoiceId, String operator, String paperLocation) {
        Invoice invoice = invoiceMapper.findById(invoiceId);
        if (invoice == null) {
            throw InvoiceException.notFound();
        }
        InvoiceArchive archive = InvoiceArchive.builder()
                .archiveId(IdGenerator.generateArchiveId())
                .invoiceId(invoiceId)
                .archiveType(ArchiveTypeEnum.PAPER.getCode())
                .storagePath(paperLocation)
                .fileName("paper_" + invoice.getInvoiceNo() + ".doc")
                .fileSize(0L)
                .md5(null)
                .archivedBy(operator != null ? operator : "system")
                .archivedAt(DateTimeUtil.now())
                .createdAt(DateTimeUtil.now())
                .build();
        archiveMapper.insert(archive);
        logger.info("纸质归档完成: invoiceId={}, location={}", invoiceId, paperLocation);
        return archive;
    }

    public InvoiceArchive getById(String archiveId) {
        return archiveMapper.findById(archiveId);
    }

    public List<InvoiceArchive> getByInvoice(String invoiceId) {
        return archiveMapper.findByInvoiceId(invoiceId);
    }

    public List<InvoiceArchive> getByType(ArchiveTypeEnum type) {
        return archiveMapper.findByType(type.getCode());
    }

    public String retrieveElectronicContent(String archiveId) throws IOException {
        InvoiceArchive archive = archiveMapper.findById(archiveId);
        if (archive == null) {
            throw new InvoiceException(404, "归档记录不存在");
        }
        if (!ArchiveTypeEnum.ELECTRONIC.getCode().equals(archive.getArchiveType())) {
            throw new InvoiceException(400, "非电子归档，无法获取内容");
        }
        Path path = Paths.get(archive.getStoragePath());
        if (!Files.exists(path)) {
            throw new InvoiceException(404, "归档文件不存在");
        }
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }

    private String generateFileName(String invoiceId, String type) {
        String dateStr = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        return String.format("%s_%s_%s.json", type, invoiceId, dateStr);
    }

    private Path buildFilePath(ArchiveTypeEnum type, String fileName) {
        String dateDir = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy/MM/dd"));
        return Paths.get(archiveBasePath, type.getCode(), dateDir, fileName);
    }

    private String calculateMD5(String content) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(content.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            return null;
        }
    }
}
