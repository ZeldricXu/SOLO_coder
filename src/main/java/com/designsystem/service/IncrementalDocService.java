package com.designsystem.service;

import com.designsystem.entity.DocParseRecord;
import com.designsystem.mapper.DocParseRecordMapper;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.treewalk.AbstractTreeIterator;
import org.eclipse.jgit.treewalk.CanonicalTreeParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class IncrementalDocService {

    private static final Logger log = LoggerFactory.getLogger(IncrementalDocService.class);

    private final DocParseRecordMapper parseRecordMapper;
    private final DocumentationService documentationService;

    public IncrementalDocService(DocParseRecordMapper parseRecordMapper,
                                  DocumentationService documentationService) {
        this.parseRecordMapper = parseRecordMapper;
        this.documentationService = documentationService;
    }

    public String calculateFileHash(byte[] content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashBytes = digest.digest(content);
            StringBuilder hexString = new StringBuilder();
            for (byte b : hashBytes) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) {
                    hexString.append('0');
                }
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 algorithm not available", e);
        }
    }

    public boolean isFileChanged(Long versionId, String filePath, byte[] content) {
        String newHash = calculateFileHash(content);
        String existingHash = parseRecordMapper.getFileHash(versionId, filePath);

        if (existingHash == null) {
            return true;
        }
        return !existingHash.equals(newHash);
    }

    public DocParseRecord getParseRecord(Long versionId, String filePath) {
        return parseRecordMapper.selectByVersionAndPath(versionId, filePath);
    }

    public List<DocParseRecord> getParseRecordsByVersion(Long versionId) {
        return parseRecordMapper.selectByVersionId(versionId);
    }

    @Transactional(rollbackFor = Exception.class)
    public DocParseRecord saveParseRecord(DocParseRecord record) {
        DocParseRecord existing = parseRecordMapper.selectByVersionAndPath(
                record.getComponentVersionId(), record.getFilePath());

        if (existing != null) {
            record.setId(existing.getId());
            parseRecordMapper.updateById(record);
            return record;
        } else {
            parseRecordMapper.insert(record);
            return record;
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> incrementalParseFiles(Long versionId, String framework,
                                                      List<MultipartFile> files, String gitRepoPath) {
        log.info("Starting incremental doc parse for versionId: {}, files: {}, gitRepo: {}",
                versionId, files.size(), gitRepoPath);

        long startTime = System.currentTimeMillis();
        Set<String> changedFiles = new HashSet<>();
        Set<String> unchangedFiles = new HashSet<>();
        Set<String> failedFiles = new HashSet<>();
        int totalProps = 0;
        int totalDocs = 0;

        String lastCommitHash = getLastParsedCommitHash(versionId);

        if (gitRepoPath != null && !gitRepoPath.isEmpty() && lastCommitHash != null) {
            changedFiles = detectGitChanges(gitRepoPath, lastCommitHash);
            log.info("Git detected {} changed files since last commit: {}", changedFiles.size(), lastCommitHash);
        }

        for (MultipartFile file : files) {
            try {
                String fileName = file.getOriginalFilename();
                String filePath = file.getName();
                byte[] content = file.getBytes();
                String fileHash = calculateFileHash(content);

                boolean fileChanged = isFileChanged(versionId, filePath, content);
                boolean gitMarkedChanged = changedFiles.contains(filePath) || changedFiles.contains(fileName);

                if (!fileChanged && !gitMarkedChanged) {
                    unchangedFiles.add(fileName);
                    log.debug("Skipping unchanged file: {}", fileName);
                    continue;
                }

                log.info("Parsing changed file: {}", fileName);
                try {
                    List<com.designsystem.entity.ComponentProp> props =
                            documentationService.extractPropsFromSource(versionId, file, framework);
                    List<com.designsystem.entity.ComponentDoc> docs =
                            documentationService.extractDocsFromSource(versionId, file);

                    totalProps += props.size();
                    totalDocs += docs.size();

                    DocParseRecord record = new DocParseRecord();
                    record.setComponentVersionId(versionId);
                    record.setFilePath(filePath);
                    record.setFileName(fileName);
                    record.setFileHash(fileHash);
                    record.setFileSize(file.getSize());
                    record.setFramework(framework);
                    record.setParseStatus("SUCCESS");
                    record.setPropCount(props.size());
                    record.setDocCount(docs.size());
                    record.setLastParsedAt(LocalDateTime.now());
                    saveParseRecord(record);

                    changedFiles.add(fileName);
                } catch (Exception e) {
                    log.error("Failed to parse file: {}, error: {}", fileName, e.getMessage());
                    failedFiles.add(fileName);

                    DocParseRecord record = new DocParseRecord();
                    record.setComponentVersionId(versionId);
                    record.setFilePath(filePath);
                    record.setFileName(fileName);
                    record.setFileHash(fileHash);
                    record.setFileSize(file.getSize());
                    record.setFramework(framework);
                    record.setParseStatus("FAILED");
                    record.setParseMessage(e.getMessage());
                    record.setLastParsedAt(LocalDateTime.now());
                    saveParseRecord(record);
                }
            } catch (IOException e) {
                log.error("Failed to read file content", e);
                failedFiles.add(file.getOriginalFilename());
            }
        }

        long duration = System.currentTimeMillis() - startTime;
        log.info("Incremental parse completed in {}ms: changed={}, unchanged={}, failed={}, props={}, docs={}",
                duration, changedFiles.size(), unchangedFiles.size(), failedFiles.size(), totalProps, totalDocs);

        Map<String, Object> result = new HashMap<>();
        result.put("totalFiles", files.size());
        result.put("changedCount", changedFiles.size());
        result.put("unchangedCount", unchangedFiles.size());
        result.put("failedCount", failedFiles.size());
        result.put("totalPropsExtracted", totalProps);
        result.put("totalDocsExtracted", totalDocs);
        result.put("changedFiles", changedFiles);
        result.put("unchangedFiles", unchangedFiles);
        result.put("failedFiles", failedFiles);
        result.put("durationMs", duration);

        if (gitRepoPath != null && !gitRepoPath.isEmpty()) {
            String currentCommitHash = getCurrentCommitHash(gitRepoPath);
            if (currentCommitHash != null) {
                updateLastParsedCommitHash(versionId, currentCommitHash);
                result.put("fromCommit", lastCommitHash);
                result.put("toCommit", currentCommitHash);
            }
        }

        return result;
    }

    public Set<String> detectGitChanges(String repoPath, String sinceCommitHash) {
        Set<String> changedFiles = new HashSet<>();

        try {
            File repoDir = new File(repoPath);
            if (!repoDir.exists() || !repoDir.isDirectory()) {
                log.warn("Git repo path does not exist: {}", repoPath);
                return changedFiles;
            }

            Repository repository = FileRepositoryBuilder.create(new File(repoDir, ".git"));
            try (Git git = new Git(repository)) {
                if (sinceCommitHash == null || sinceCommitHash.isEmpty()) {
                    return changedFiles;
                }

                RevWalk walk = new RevWalk(repository);
                RevCommit sinceCommit = walk.parseCommit(repository.resolve(sinceCommitHash));
                RevCommit headCommit = walk.parseCommit(repository.resolve("HEAD"));

                try (ObjectReader reader = repository.newObjectReader()) {
                    AbstractTreeIterator oldTreeIter = new CanonicalTreeParser();
                    oldTreeIter.reset(reader, sinceCommit.getTree());

                    AbstractTreeIterator newTreeIter = new CanonicalTreeParser();
                    newTreeIter.reset(reader, headCommit.getTree());

                    List<DiffEntry> diffs = git.diff()
                            .setNewTree(newTreeIter)
                            .setOldTree(oldTreeIter)
                            .call();

                    for (DiffEntry diff : diffs) {
                        if (diff.getChangeType() == DiffEntry.ChangeType.DELETE) {
                            changedFiles.add(diff.getOldPath());
                        } else {
                            changedFiles.add(diff.getNewPath());
                        }
                    }
                }
                walk.close();
            }
            repository.close();
        } catch (IOException | GitAPIException e) {
            log.error("Failed to detect git changes: {}", e.getMessage());
        }

        return changedFiles;
    }

    public String getCurrentCommitHash(String repoPath) {
        try {
            File repoDir = new File(repoPath);
            if (!repoDir.exists() || !repoDir.isDirectory()) {
                return null;
            }

            Repository repository = FileRepositoryBuilder.create(new File(repoDir, ".git"));
            try (Git git = new Git(repository)) {
                return repository.resolve("HEAD").getName();
            } finally {
                repository.close();
            }
        } catch (IOException e) {
            log.error("Failed to get current commit hash: {}", e.getMessage());
            return null;
        }
    }

    private String getLastParsedCommitHash(Long versionId) {
        List<DocParseRecord> records = parseRecordMapper.selectByVersionId(versionId);
        if (records.isEmpty()) {
            return null;
        }
        return records.stream()
                .map(DocParseRecord::getLastCommitHash)
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(null);
    }

    private void updateLastParsedCommitHash(Long versionId, String commitHash) {
        List<DocParseRecord> records = parseRecordMapper.selectByVersionId(versionId);
        for (DocParseRecord record : records) {
            record.setLastCommitHash(commitHash);
            parseRecordMapper.updateById(record);
        }
    }

    public Map<String, Object> getParseStatistics(Long versionId) {
        List<DocParseRecord> records = parseRecordMapper.selectByVersionId(versionId);

        long successCount = records.stream().filter(r -> "SUCCESS".equals(r.getParseStatus())).count();
        long failedCount = records.stream().filter(r -> "FAILED".equals(r.getParseStatus())).count();
        int totalProps = records.stream().mapToInt(r -> r.getPropCount() != null ? r.getPropCount() : 0).sum();
        int totalDocs = records.stream().mapToInt(r -> r.getDocCount() != null ? r.getDocCount() : 0).sum();

        Map<String, Object> stats = new HashMap<>();
        stats.put("totalFiles", records.size());
        stats.put("successCount", successCount);
        stats.put("failedCount", failedCount);
        stats.put("totalProps", totalProps);
        stats.put("totalDocs", totalDocs);
        stats.put("records", records);

        return stats;
    }

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> fullReparse(Long versionId, String framework,
                                            List<MultipartFile> files, String gitRepoPath) {
        parseRecordMapper.selectByVersionId(versionId).forEach(r -> parseRecordMapper.deleteById(r.getId()));
        log.info("Cleared all parse records for versionId: {}, starting full reparse", versionId);
        return incrementalParseFiles(versionId, framework, files, gitRepoPath);
    }
}
