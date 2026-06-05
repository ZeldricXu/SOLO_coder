package com.company.dbstudio.core.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Base64;
import java.util.List;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

public final class IOUtils {

    private static final Logger logger = LoggerFactory.getLogger(IOUtils.class);
    private static final int BUFFER_SIZE = 8192;

    private IOUtils() {
    }

    public static String readToString(InputStream inputStream) throws IOException {
        return readToString(inputStream, StandardCharsets.UTF_8);
    }

    public static String readToString(InputStream inputStream, Charset charset) throws IOException {
        if (inputStream == null) {
            return null;
        }
        try (ByteArrayOutputStream result = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[BUFFER_SIZE];
            int length;
            while ((length = inputStream.read(buffer)) != -1) {
                result.write(buffer, 0, length);
            }
            return result.toString(charset.name());
        }
    }

    public static String readFileToString(String filePath) throws IOException {
        return readFileToString(Paths.get(filePath), StandardCharsets.UTF_8);
    }

    public static String readFileToString(Path filePath) throws IOException {
        return readFileToString(filePath, StandardCharsets.UTF_8);
    }

    public static String readFileToString(Path filePath, Charset charset) throws IOException {
        return Files.readString(filePath, charset);
    }

    public static List<String> readLines(Path filePath) throws IOException {
        return Files.readAllLines(filePath, StandardCharsets.UTF_8);
    }

    public static List<String> readLines(Path filePath, Charset charset) throws IOException {
        return Files.readAllLines(filePath, charset);
    }

    public static void writeStringToFile(String content, String filePath) throws IOException {
        writeStringToFile(content, Paths.get(filePath), StandardCharsets.UTF_8);
    }

    public static void writeStringToFile(String content, Path filePath) throws IOException {
        writeStringToFile(content, filePath, StandardCharsets.UTF_8);
    }

    public static void writeStringToFile(String content, Path filePath, Charset charset) throws IOException {
        Files.writeString(filePath, content, charset,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
    }

    public static void writeLines(List<String> lines, Path filePath) throws IOException {
        Files.write(filePath, lines, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
    }

    public static byte[] readToBytes(InputStream inputStream) throws IOException {
        if (inputStream == null) {
            return null;
        }
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[BUFFER_SIZE];
            int length;
            while ((length = inputStream.read(buffer)) != -1) {
                baos.write(buffer, 0, length);
            }
            return baos.toByteArray();
        }
    }

    public static byte[] readFileToBytes(Path filePath) throws IOException {
        return Files.readAllBytes(filePath);
    }

    public static void writeBytesToFile(byte[] bytes, Path filePath) throws IOException {
        Files.write(filePath, bytes,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
    }

    public static long copy(InputStream input, OutputStream output) throws IOException {
        byte[] buffer = new byte[BUFFER_SIZE];
        long count = 0;
        int n;
        while ((n = input.read(buffer)) != -1) {
            output.write(buffer, 0, n);
            count += n;
        }
        return count;
    }

    public static void closeQuietly(Closeable closeable) {
        if (closeable != null) {
            try {
                closeable.close();
            } catch (IOException e) {
                logger.debug("Error closing resource", e);
            }
        }
    }

    public static String compress(String data) throws IOException {
        if (data == null) {
            return null;
        }
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
             GZIPOutputStream gzipOut = new GZIPOutputStream(baos)) {
            gzipOut.write(data.getBytes(StandardCharsets.UTF_8));
            gzipOut.finish();
            return Base64.getEncoder().encodeToString(baos.toByteArray());
        }
    }

    public static String decompress(String compressedData) throws IOException {
        if (compressedData == null) {
            return null;
        }
        byte[] compressed = Base64.getDecoder().decode(compressedData);
        try (GZIPInputStream gzipIn = new GZIPInputStream(new ByteArrayInputStream(compressed));
             ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            copy(gzipIn, baos);
            return baos.toString(StandardCharsets.UTF_8);
        }
    }

    public static String toBase64(byte[] data) {
        return data != null ? Base64.getEncoder().encodeToString(data) : null;
    }

    public static byte[] fromBase64(String base64) {
        return base64 != null ? Base64.getDecoder().decode(base64) : null;
    }

    public static boolean fileExists(Path path) {
        return path != null && Files.exists(path);
    }

    public static boolean isDirectory(Path path) {
        return path != null && Files.isDirectory(path);
    }

    public static boolean isReadable(Path path) {
        return path != null && Files.isReadable(path);
    }

    public static boolean isWritable(Path path) {
        return path != null && Files.isWritable(path);
    }

    public static void createDirectories(Path path) throws IOException {
        if (path != null && !Files.exists(path)) {
            Files.createDirectories(path);
        }
    }

    public static void deleteFile(Path path) throws IOException {
        if (path != null && Files.exists(path)) {
            Files.delete(path);
        }
    }

    public static void deleteDirectory(Path path) throws IOException {
        if (path == null || !Files.exists(path)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(path)) {
            walk.sorted((a, b) -> b.compareTo(a))
                    .forEach(p -> {
                        try {
                            Files.delete(p);
                        } catch (IOException e) {
                            logger.warn("Failed to delete: {}", p, e);
                        }
                    });
        }
    }

    public static long getFileSize(Path path) throws IOException {
        if (path == null || !Files.exists(path)) {
            return 0;
        }
        return Files.size(path);
    }

    public static String getFileExtension(Path path) {
        if (path == null) {
            return null;
        }
        String name = path.getFileName().toString();
        int dotIndex = name.lastIndexOf('.');
        return dotIndex > 0 ? name.substring(dotIndex + 1).toLowerCase() : "";
    }

    public static String getFileNameWithoutExtension(Path path) {
        if (path == null) {
            return null;
        }
        String name = path.getFileName().toString();
        int dotIndex = name.lastIndexOf('.');
        return dotIndex > 0 ? name.substring(0, dotIndex) : name;
    }

    public static Path getUserHomeDir() {
        return Paths.get(System.getProperty("user.home"));
    }

    public static Path getAppDataDir(String appName) {
        String os = System.getProperty("os.name").toLowerCase();
        Path baseDir;
        if (os.contains("win")) {
            String appData = System.getenv("APPDATA");
            baseDir = appData != null ? Paths.get(appData) : getUserHomeDir();
        } else if (os.contains("mac")) {
            baseDir = getUserHomeDir().resolve("Library").resolve("Application Support");
        } else {
            baseDir = getUserHomeDir().resolve(".config");
        }
        return baseDir.resolve(appName);
    }

    public static void ensureDirectoryExists(Path dir) throws IOException {
        if (dir != null && !Files.exists(dir)) {
            Files.createDirectories(dir);
        }
    }
}
