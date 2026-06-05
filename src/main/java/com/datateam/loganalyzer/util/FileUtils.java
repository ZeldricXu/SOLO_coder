package com.datateam.loganalyzer.util;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.GZIPInputStream;

public class FileUtils {

    public static List<String> readAllLines(String filePath) throws IOException {
        return readAllLines(new File(filePath));
    }

    public static List<String> readAllLines(File file) throws IOException {
        List<String> lines = new ArrayList<>();

        if (!file.exists()) {
            throw new IOException("File not found: " + file.getAbsolutePath());
        }

        BufferedReader reader;
        if (file.getName().endsWith(".gz")) {
            reader = new BufferedReader(new InputStreamReader(
                new GZIPInputStream(new FileInputStream(file)), StandardCharsets.UTF_8));
        } else {
            reader = new BufferedReader(new InputStreamReader(
                new FileInputStream(file), StandardCharsets.UTF_8));
        }

        String line;
        while ((line = reader.readLine()) != null) {
            lines.add(line);
        }
        reader.close();

        return lines;
    }

    public static List<String> readLinesWithLimit(String filePath, int limit) throws IOException {
        return readLinesWithLimit(new File(filePath), limit);
    }

    public static List<String> readLinesWithLimit(File file, int limit) throws IOException {
        List<String> lines = new ArrayList<>();

        if (!file.exists()) {
            throw new IOException("File not found: " + file.getAbsolutePath());
        }

        BufferedReader reader = new BufferedReader(new InputStreamReader(
            new FileInputStream(file), StandardCharsets.UTF_8));

        String line;
        int count = 0;
        while ((line = reader.readLine()) != null && count < limit) {
            lines.add(line);
            count++;
        }
        reader.close();

        return lines;
    }

    public static List<File> expandFilePaths(List<String> paths) throws IOException {
        List<File> files = new ArrayList<>();

        for (String path : paths) {
            File file = new File(path);
            if (file.isDirectory()) {
                File[] dirFiles = file.listFiles((f) -> f.isFile() &&
                    (f.getName().endsWith(".log") || f.getName().endsWith(".txt") ||
                        f.getName().endsWith(".json") || f.getName().endsWith(".gz")));
                if (dirFiles != null) {
                    for (File f : dirFiles) {
                        files.add(f);
                    }
                }
            } else if (file.isFile()) {
                files.add(file);
            } else {
                throw new IOException("Invalid path: " + path);
            }
        }

        return files;
    }

    public static List<String> readFromStdin() throws IOException {
        List<String> lines = new ArrayList<>();
        BufferedReader reader = new BufferedReader(
            new InputStreamReader(System.in, StandardCharsets.UTF_8));
        String line;
        while ((line = reader.readLine()) != null) {
            lines.add(line);
        }
        return lines;
    }
}
