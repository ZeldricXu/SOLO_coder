package com.llmgateway.document.service;

import cn.hutool.core.util.HexUtil;
import cn.hutool.crypto.digest.DigestUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.llmgateway.common.api.PageResult;
import com.llmgateway.common.constant.CommonConstants;
import com.llmgateway.common.exception.BusinessException;
import com.llmgateway.common.util.IdGenerator;
import com.llmgateway.document.dto.DocumentUploadDTO;
import com.llmgateway.document.entity.Document;
import com.llmgateway.document.mapper.DocumentMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDateTime;

@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentService {

    private final DocumentMapper documentMapper;

    @Transactional(rollbackFor = Exception.class)
    public Document upload(DocumentUploadDTO dto) {
        Document document = new Document();
        document.setDocumentId(IdGenerator.generateDocumentId());
        document.setTitle(dto.getTitle() != null ? dto.getTitle() : dto.getFileName());
        document.setFileName(dto.getFileName());
        document.setFileType(dto.getFileType());
        document.setFileSize(dto.getFileSize());
        document.setCharset(dto.getCharset() != null ? dto.getCharset() : "UTF-8");
        document.setLanguage(dto.getLanguage() != null ? dto.getLanguage() : "zh-CN");
        document.setStatus(CommonConstants.STATUS_PENDING);
        document.setMetadata(dto.getMetadata());
        document.setCreatedBy(dto.getCreatedBy());

        if (dto.getContent() != null) {
            document.setContentHash(DigestUtil.sha256Hex(dto.getContent()));
            document.setFileSize((long) dto.getContent().getBytes().length);
        }

        documentMapper.insert(document);
        log.info("文档上传成功: documentId={}", document.getDocumentId());
        return document;
    }

    public Document getById(String documentId) {
        Document document = documentMapper.selectById(documentId);
        if (document == null) {
            throw new BusinessException(404, "文档不存在");
        }
        return document;
    }

    public PageResult<Document> list(String fileType, String status, Integer pageNum, Integer pageSize) {
        LambdaQueryWrapper<Document> wrapper = new LambdaQueryWrapper<>();
        if (fileType != null) {
            wrapper.eq(Document::getFileType, fileType);
        }
        if (status != null) {
            wrapper.eq(Document::getStatus, status);
        }
        wrapper.eq(Document::getDeleted, 0);
        wrapper.orderByDesc(Document::getCreatedAt);

        IPage<Document> page = documentMapper.selectPage(new Page<>(pageNum, pageSize), wrapper);
        return PageResult.of(page);
    }

    @Transactional(rollbackFor = Exception.class)
    public Document updateStatus(String documentId, String status) {
        Document document = getById(documentId);
        document.setStatus(status);
        document.setUpdatedAt(LocalDateTime.now());
        documentMapper.updateById(document);
        return document;
    }

    @Transactional(rollbackFor = Exception.class)
    public void delete(String documentId) {
        Document document = getById(documentId);
        document.setDeleted(1);
        document.setUpdatedAt(LocalDateTime.now());
        documentMapper.updateById(document);
    }
}
