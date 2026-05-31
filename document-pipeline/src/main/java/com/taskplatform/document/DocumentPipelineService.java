package com.taskplatform.document;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.taskplatform.common.exception.BusinessException;
import com.taskplatform.common.util.IdGenerator;
import com.taskplatform.common.util.JsonUtil;
import com.taskplatform.document.parser.TextParser;
import com.taskplatform.persistence.entity.Document;
import com.taskplatform.persistence.entity.DocumentChunk;
import com.taskplatform.persistence.mapper.DocumentChunkMapper;
import com.taskplatform.persistence.mapper.DocumentMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentPipelineService {

    private final List<DocumentParser> parsers;
    private final DocumentMapper documentMapper;
    private final DocumentChunkMapper documentChunkMapper;
    private final TextParser fallbackParser;

    @Value("${document.storage.path:./documents}")
    private String storagePath;

    @Value("${document.chunk.size:512}")
    private int chunkSize;

    @Value("${document.chunk.overlap:50}")
    private int chunkOverlap;

    @Transactional
    public Document uploadDocument(MultipartFile file, String title, String createdBy) throws IOException {
        String originalFilename = file.getOriginalFilename();
        String fileType = getFileExtension(originalFilename);

        Path storageDir = Paths.get(storagePath);
        Files.createDirectories(storageDir);

        String docId = IdGenerator.generateDocId();
        Path targetPath = storageDir.resolve(docId + "_" + originalFilename);
        Files.copy(file.getInputStream(), targetPath, StandardCopyOption.REPLACE_EXISTING);

        String content = parseDocument(file.getInputStream(), fileType);
        String checksum = calculateChecksum(targetPath);

        Document document = new Document();
        document.setDocId(docId);
        document.setTitle(title != null ? title : originalFilename);
        document.setContent(content);
        document.setFilePath(targetPath.toString());
        document.setFileType(fileType);
        document.setFileSize(file.getSize());
        document.setChecksum(checksum);
        document.setParseStatus("PARSED");
        document.setCreatedBy(createdBy);
        document.setProcessedAt(LocalDateTime.now());

        Map<String, Object> metadata = new HashMap<>();
        metadata.put("originalFilename", originalFilename);
        metadata.put("contentLength", content.length());
        metadata.put("parsedWith", getParser(fileType).getClass().getSimpleName());
        document.setMetadata(JsonUtil.toJson(metadata));

        documentMapper.insert(document);

        List<DocumentChunk> chunks = createChunks(document);
        document.setChunkCount(chunks.size());
        documentMapper.updateById(document);

        log.info("Document uploaded: {} - {}", docId, title);
        return document;
    }

    private String parseDocument(java.io.InputStream inputStream, String fileType) throws IOException {
        DocumentParser parser = getParser(fileType);
        return parser.parse(inputStream, fileType);
    }

    private DocumentParser getParser(String fileType) {
        return parsers.stream()
                .filter(p -> p.supports(fileType))
                .findFirst()
                .orElse(fallbackParser);
    }

    private List<DocumentChunk> createChunks(Document document) {
        List<DocumentChunk> chunks = new ArrayList<>();
        String content = document.getContent();
        if (content == null || content.isEmpty()) {
            return chunks;
        }

        int index = 0;
        int position = 0;
        int contentLength = content.length();

        while (position < contentLength) {
            int end = Math.min(position + chunkSize, contentLength);

            if (end < contentLength) {
                int lastPeriod = content.lastIndexOf('.', end);
                int lastNewline = content.lastIndexOf('\n', end);
                int lastSpace = content.lastIndexOf(' ', end);
                int splitPoint = Math.max(Math.max(lastPeriod, lastNewline), lastSpace);
                if (splitPoint > position + chunkSize / 2) {
                    end = splitPoint + 1;
                }
            }

            String chunkContent = content.substring(position, end).trim();
            if (!chunkContent.isEmpty()) {
                DocumentChunk chunk = new DocumentChunk();
                chunk.setChunkId(IdGenerator.generate("chunk_"));
                chunk.setDocId(document.getDocId());
                chunk.setChunkIndex(index);
                chunk.setContent(chunkContent);
                chunk.setTokenCount(countTokens(chunkContent));

                Map<String, Object> metadata = new HashMap<>();
                metadata.put("startPosition", position);
                metadata.put("endPosition", end);
                metadata.put("length", chunkContent.length());
                chunk.setMetadata(JsonUtil.toJson(metadata));

                documentChunkMapper.insert(chunk);
                chunks.add(chunk);
            }

            position = end - chunkOverlap;
            if (position <= 0 || position >= contentLength) {
                break;
            }
            index++;
        }

        return chunks;
    }

    public List<DocumentChunk> vectorizeChunks(String docId, String embeddingModel) {
        List<DocumentChunk> chunks = documentChunkMapper.selectList(
                new LambdaQueryWrapper<DocumentChunk>()
                        .eq(DocumentChunk::getDocId, docId)
                        .orderByAsc(DocumentChunk::getChunkIndex)
        );

        for (DocumentChunk chunk : chunks) {
            float[] mockEmbedding = generateMockEmbedding(chunk.getContent());
            chunk.setEmbedding(floatArrayToByteArray(mockEmbedding));
            chunk.setEmbeddingModel(embeddingModel);
            chunk.setVectorDimension(mockEmbedding.length);
            documentChunkMapper.updateById(chunk);
        }

        log.info("Vectorized {} chunks for document: {}", chunks.size(), docId);
        return chunks;
    }

    private float[] generateMockEmbedding(String content) {
        float[] embedding = new float[1536];
        int hash = content.hashCode();
        Random random = new Random(hash);
        for (int i = 0; i < embedding.length; i++) {
            embedding[i] = (random.nextFloat() - 0.5f) * 2;
        }
        return normalize(embedding);
    }

    private float[] normalize(float[] vector) {
        double sum = 0;
        for (float v : vector) {
            sum += v * v;
        }
        double norm = Math.sqrt(sum);
        if (norm > 0) {
            for (int i = 0; i < vector.length; i++) {
                vector[i] /= norm;
            }
        }
        return vector;
    }

    private byte[] floatArrayToByteArray(float[] floats) {
        byte[] bytes = new byte[floats.length * 4];
        for (int i = 0; i < floats.length; i++) {
            int bits = Float.floatToIntBits(floats[i]);
            bytes[i * 4] = (byte) (bits & 0xFF);
            bytes[i * 4 + 1] = (byte) ((bits >> 8) & 0xFF);
            bytes[i * 4 + 2] = (byte) ((bits >> 16) & 0xFF);
            bytes[i * 4 + 3] = (byte) ((bits >> 24) & 0xFF);
        }
        return bytes;
    }

    private int countTokens(String text) {
        return text.split("\\s+").length;
    }

    private String getFileExtension(String filename) {
        if (filename == null) return "txt";
        int dotIndex = filename.lastIndexOf('.');
        return dotIndex > 0 ? filename.substring(dotIndex + 1).toLowerCase() : "txt";
    }

    private String calculateChecksum(Path file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] fileBytes = Files.readAllBytes(file);
            byte[] hash = digest.digest(fileBytes);
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (Exception e) {
            throw new IOException("Failed to calculate checksum", e);
        }
    }

    public Document getDocument(String docId) {
        Document document = documentMapper.selectOne(
                new LambdaQueryWrapper<Document>().eq(Document::getDocId, docId)
        );
        if (document == null) {
            throw new BusinessException(404, "DOC_NOT_FOUND", "Document not found: " + docId);
        }
        return document;
    }
}
