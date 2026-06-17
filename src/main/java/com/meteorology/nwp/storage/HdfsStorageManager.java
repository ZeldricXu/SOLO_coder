package com.meteorology.nwp.storage;

import com.meteorology.nwp.common.NWPConfig;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.fs.FSDataInputStream;
import org.apache.hadoop.fs.FSDataOutputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.net.URI;
import java.nio.file.Files;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

public class HdfsStorageManager {
    private static final Logger logger = LoggerFactory.getLogger(HdfsStorageManager.class);
    private final NWPConfig config;
    private final String hdfsUri;
    private final String baseDir;
    private final short replication;
    private final long blockSize;
    private final boolean useCompression;
    private transient FileSystem fs;
    private final Configuration hadoopConf;
    private static final DateTimeFormatter DATE_DIR_FMT = DateTimeFormatter
            .ofPattern("yyyy/MM/dd/HH").withZone(ZoneOffset.UTC);

    public HdfsStorageManager(NWPConfig config) {
        this.config = config;
        this.hdfsUri = config.getString("nwp.storage.hdfs.uri", "hdfs://localhost:9000");
        this.baseDir = config.getString("nwp.storage.hdfs.baseDir", "/nwp/data");
        this.replication = (short) config.getInt("nwp.storage.hdfs.replication", 3);
        this.blockSize = config.getLong("nwp.storage.hdfs.blockSize", 256 * 1024 * 1024L);
        this.useCompression = config.getBoolean("nwp.storage.hdfs.compression", true);
        this.hadoopConf = new Configuration();
        hadoopConf.set("fs.hdfs.impl", org.apache.hadoop.hdfs.DistributedFileSystem.class.getName());
        hadoopConf.set("fs.file.impl", org.apache.hadoop.fs.LocalFileSystem.class.getName());
        hadoopConf.setInt("dfs.replication", replication);
        hadoopConf.setLong("dfs.blocksize", blockSize);
        hadoopConf.setInt("ipc.client.connect.max.retries", 3);
        hadoopConf.setInt("ipc.client.connect.timeout", 10000);
        logger.info("HDFS存储初始化: URI={} 根目录={} 副本={} 块={}MB gzip={}",
                hdfsUri, baseDir, replication, blockSize / 1048576, useCompression);
    }

    public synchronized FileSystem getFileSystem() throws IOException {
        if (fs == null || !fs.isFileClosed(new Path("/tmp/.probe"))) {
            try {
                fs = FileSystem.get(new URI(hdfsUri), hadoopConf, "hdfs");
                fs.setWorkingDirectory(new Path(baseDir));
                Path basePath = new Path(baseDir);
                if (!fs.exists(basePath)) {
                    fs.mkdirs(basePath);
                    logger.info("创建HDFS根目录: {}", baseDir);
                }
            } catch (Exception e) {
                logger.warn("连接HDFS失败，使用本地文件系统回退: {}", e.getMessage());
                fs = FileSystem.getLocal(hadoopConf);
                Path localBase = new Path("./hdfs_data" + baseDir);
                if (!fs.exists(localBase)) fs.mkdirs(localBase);
            }
        }
        return fs;
    }

    public String storeFile(String category, Instant analysisTime, int forecastHour,
                             String varName, String format, byte[] data) throws IOException {
        String relPath = buildPath(category, analysisTime, forecastHour, varName, format);
        Path destPath = new Path(baseDir, relPath);
        Path parent = destPath.getParent();
        FileSystem fsys = getFileSystem();
        if (!fsys.exists(parent)) fsys.mkdirs(parent);
        if (useCompression && format.endsWith("bin")) {
            try (FSDataOutputStream out = fsys.create(destPath, true, 65536, replication, blockSize);
                 GZIPOutputStream gzip = new GZIPOutputStream(out, 65536)) {
                gzip.write(data);
            }
        } else {
            try (FSDataOutputStream out = fsys.create(destPath, true, 65536, replication, blockSize)) {
                out.write(data);
            }
        }
        long size = fsys.getFileStatus(destPath).getLen();
        logger.debug("写入HDFS: {} ({} bytes)", destPath, size);
        return destPath.toString();
    }

    public byte[] retrieveFile(String hdfsPath) throws IOException {
        Path path = new Path(hdfsPath);
        FileSystem fsys = getFileSystem();
        if (!fsys.exists(path)) {
            throw new FileNotFoundException("HDFS文件不存在: " + hdfsPath);
        }
        long size = fsys.getFileStatus(path).getLen();
        byte[] buf = new byte[(int) Math.min(Integer.MAX_VALUE, size)];
        try (FSDataInputStream in = fsys.open(path, 65536)) {
            if (useCompression && hdfsPath.endsWith(".gz")) {
                try (GZIPInputStream gzip = new GZIPInputStream(in, 65536);
                     ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
                    byte[] tmp = new byte[65536];
                    int r;
                    while ((r = gzip.read(tmp)) > 0) baos.write(tmp, 0, r);
                    return baos.toByteArray();
                }
            }
            int totalRead = 0;
            while (totalRead < buf.length) {
                int r = in.read(buf, totalRead, buf.length - totalRead);
                if (r < 0) break;
                totalRead += r;
            }
        }
        return buf;
    }

    public String storeLocalTempFile(String localPath, String category, Instant analysisTime,
                                     int forecastHour, String varName, String format) throws IOException {
        File src = new File(localPath);
        if (!src.exists()) throw new FileNotFoundException(localPath);
        byte[] data = Files.readAllBytes(src.toPath());
        return storeFile(category, analysisTime, forecastHour, varName, format, data);
    }

    public void retrieveToLocalFile(String hdfsPath, String localPath) throws IOException {
        byte[] data = retrieveFile(hdfsPath);
        File dest = new File(localPath);
        dest.getParentFile().mkdirs();
        Files.write(dest.toPath(), data);
    }

    public List<String> listFiles(String category, Instant analysisTime, String suffix) {
        String dateDir = DATE_DIR_FMT.format(analysisTime);
        Path searchPath = new Path(new Path(baseDir, category), dateDir);
        List<String> files = new ArrayList<>();
        try {
            FileSystem fsys = getFileSystem();
            if (!fsys.exists(searchPath)) return files;
            for (org.apache.hadoop.fs.FileStatus st : fsys.globStatus(
                    new Path(searchPath, "*." + (suffix == null ? "*" : suffix)))) {
                files.add(st.getPath().toString());
            }
        } catch (IOException e) {
            logger.error("列举HDFS文件失败 {}: {}", searchPath, e.getMessage());
        }
        return files;
    }

    public boolean deleteFile(String hdfsPath) {
        try {
            getFileSystem().delete(new Path(hdfsPath), false);
            return true;
        } catch (IOException e) {
            logger.warn("删除HDFS文件失败: {}", e.getMessage());
            return false;
        }
    }

    public long getUsedBytes() {
        try {
            return getFileSystem().getContentSummary(new Path(baseDir)).getLength();
        } catch (Exception e) {
            return -1;
        }
    }

    private String buildPath(String category, Instant analysisTime, int forecastHour,
                              String varName, String format) {
        String dateDir = DATE_DIR_FMT.format(analysisTime);
        String suffix = format.toLowerCase();
        if (useCompression && "bin".equals(suffix)) suffix = "bin.gz";
        return String.format("%s/%s/f%03d_%s.%s",
                category, dateDir, forecastHour, varName, suffix);
    }

    public void close() {
        try {
            if (fs != null) fs.close();
        } catch (IOException ignored) {}
        fs = null;
    }
}
