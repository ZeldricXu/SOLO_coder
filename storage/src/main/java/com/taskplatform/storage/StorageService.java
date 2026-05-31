package com.taskplatform.storage;

import com.taskplatform.common.exception.BusinessException;
import com.taskplatform.common.util.IdGenerator;
import com.taskplatform.common.util.JsonUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.FileUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

@Slf4j
@Service
@RequiredArgsConstructor
public class StorageService {

    @Value("${storage.backup.directory:./backups}")
    private String backupDirectory;

    @Value("${storage.compression.enabled:true}")
    private boolean compressionEnabled;

    public Map<String, Object> createBackup(String sourcePath, String backupType, String createdBy) throws IOException {
        Path source = Paths.get(sourcePath);
        if (!Files.exists(source)) {
            throw new BusinessException(404, "SOURCE_NOT_FOUND", "Source path does not exist: " + sourcePath);
        }

        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        String backupId = IdGenerator.generate("backup_");
        String backupFileName = backupType + "_" + timestamp + ".tar" + (compressionEnabled ? ".gz" : "");
        Path targetPath = Paths.get(backupDirectory, backupId, backupFileName);

        Files.createDirectories(targetPath.getParent());

        long size = 0;
        if (Files.isDirectory(source)) {
            size = createDirectoryBackup(source, targetPath);
        } else {
            size = createFileBackup(source, targetPath);
        }

        String checksum = calculateChecksum(targetPath);

        Map<String, Object> result = new HashMap<>();
        result.put("backupId", backupId);
        result.put("sourcePath", sourcePath);
        result.put("targetPath", targetPath.toString());
        result.put("sizeBytes", size);
        result.put("checksum", checksum);
        result.put("backupType", backupType);
        result.put("createdAt", LocalDateTime.now().toString());
        result.put("createdBy", createdBy);

        log.info("Backup created: {} -> {}", backupId, targetPath);
        return result;
    }

    private long createDirectoryBackup(Path source, Path target) throws IOException {
        try (OutputStream os = Files.newOutputStream(target);
             GZIPOutputStream gzos = compressionEnabled ? new GZIPOutputStream(os) : null;
             BufferedOutputStream bos = new BufferedOutputStream(compressionEnabled ? gzos : os);
             ObjectOutputStream oos = new ObjectOutputStream(bos)) {

            Files.walk(source)
                    .filter(Files::isRegularFile)
                    .forEach(path -> {
                        try {
                            Path relativePath = source.relativize(path);
                            byte[] content = Files.readAllBytes(path);
                            oos.writeObject(relativePath.toString());
                            oos.writeObject(content);
                        } catch (IOException e) {
                            throw new UncheckedIOException(e);
                        }
                    });
        }
        return Files.size(target);
    }

    private long createFileBackup(Path source, Path target) throws IOException {
        if (compressionEnabled) {
            try (InputStream is = Files.newInputStream(source);
                 GZIPOutputStream gzos = new GZIPOutputStream(Files.newOutputStream(target))) {
                byte[] buffer = new byte[8192];
                int len;
                while ((len = is.read(buffer)) != -1) {
                    gzos.write(buffer, 0, len);
                }
            }
        } else {
            Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
        return Files.size(target);
    }

    public Map<String, Object> restoreBackup(String backupId, String restorePath) throws IOException {
        Path backupDir = Paths.get(backupDirectory, backupId);
        if (!Files.exists(backupDir)) {
            throw new BusinessException(404, "BACKUP_NOT_FOUND", "Backup not found: " + backupId);
        }

        Path backupFile = Files.list(backupDir).findFirst()
                .orElseThrow(() -> new BusinessException(404, "BACKUP_EMPTY", "Backup directory is empty"));

        Path target = Paths.get(restorePath);
        Files.createDirectories(target);

        long restoredCount = 0;
        if (backupFile.getFileName().toString().endsWith(".tar.gz") ||
            backupFile.getFileName().toString().endsWith(".tar")) {
            restoredCount = restoreDirectoryBackup(backupFile, target);
        } else {
            restoreFileBackup(backupFile, target);
            restoredCount = 1;
        }

        Map<String, Object> result = new HashMap<>();
        result.put("backupId", backupId);
        result.put("restoredTo", restorePath);
        result.put("filesRestored", restoredCount);
        result.put("restoredAt", LocalDateTime.now().toString());

        log.info("Backup restored: {} -> {}", backupId, restorePath);
        return result;
    }

    private long restoreDirectoryBackup(Path backupFile, Path target) throws IOException {
        long count = 0;
        try (InputStream is = Files.newInputStream(backupFile);
             GZIPInputStream gzis = backupFile.getFileName().toString().endsWith(".gz") ?
                     new GZIPInputStream(is) : null;
             BufferedInputStream bis = new BufferedInputStream(gzis != null ? gzis : is);
             ObjectInputStream ois = new ObjectInputStream(bis)) {

            while (true) {
                try {
                    String relativePath = (String) ois.readObject();
                    byte[] content = (byte[]) ois.readObject();
                    Path outputPath = target.resolve(relativePath);
                    Files.createDirectories(outputPath.getParent());
                    Files.write(outputPath, content);
                    count++;
                } catch (EOFException e) {
                    break;
                } catch (ClassNotFoundException e) {
                    throw new IOException("Invalid backup format", e);
                }
            }
        }
        return count;
    }

    private void restoreFileBackup(Path backupFile, Path target) throws IOException {
        String fileName = backupFile.getFileName().toString();
        if (fileName.endsWith(".gz")) {
            fileName = fileName.substring(0, fileName.length() - 3);
            try (GZIPInputStream gzis = new GZIPInputStream(Files.newInputStream(backupFile));
                 OutputStream os = Files.newOutputStream(target.resolve(fileName))) {
                byte[] buffer = new byte[8192];
                int len;
                while ((len = gzis.read(buffer)) != -1) {
                    os.write(buffer, 0, len);
                }
            }
        } else {
            Files.copy(backupFile, target.resolve(fileName), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private String calculateChecksum(Path file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream is = Files.newInputStream(file)) {
                byte[] buffer = new byte[8192];
                int len;
                while ((len = is.read(buffer)) != -1) {
                    digest.update(buffer, 0, len);
                }
            }
            byte[] hash = digest.digest();
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

    public void deleteBackup(String backupId) throws IOException {
        Path backupDir = Paths.get(backupDirectory, backupId);
        if (Files.exists(backupDir)) {
            FileUtils.deleteDirectory(backupDir.toFile());
            log.info("Backup deleted: {}", backupId);
        }
    }

    public List<Map<String, Object>> listBackups() throws IOException {
        List<Map<String, Object>> backups = new ArrayList<>();
        Path backupDir = Paths.get(backupDirectory);
        if (!Files.exists(backupDir)) {
            return backups;
        }

        Files.list(backupDir).forEach(dir -> {
            try {
                Map<String, Object> info = new HashMap<>();
                info.put("backupId", dir.getFileName().toString());
                info.put("createdAt", Files.getLastModifiedTime(dir).toString());
                long size = Files.walk(dir)
                        .filter(Files::isRegularFile)
                        .mapToLong(p -> {
                            try {
                                return Files.size(p);
                            } catch (IOException e) {
                                return 0;
                            }
                        })
                        .sum();
                info.put("sizeBytes", size);
                backups.add(info);
            } catch (IOException e) {
                log.warn("Failed to read backup info: {}", dir, e);
            }
        });

        return backups;
    }
}
