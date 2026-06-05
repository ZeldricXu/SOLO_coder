package com.datateam.loganalyzer.util;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.Map;

public class JsonUtils {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    static {
        OBJECT_MAPPER.registerModule(new JavaTimeModule());
        OBJECT_MAPPER.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        OBJECT_MAPPER.configure(SerializationFeature.FAIL_ON_EMPTY_BEANS, false);
    }

    public static ObjectMapper getObjectMapper() {
        return OBJECT_MAPPER;
    }

    public static String toJson(Object obj) throws IOException {
        return OBJECT_MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(obj);
    }

    public static <T> T fromJson(String json, Class<T> clazz) throws IOException {
        return OBJECT_MAPPER.readValue(json, clazz);
    }

    public static <T> T fromJson(String json, TypeReference<T> typeRef) throws IOException {
        return OBJECT_MAPPER.readValue(json, typeRef);
    }

    public static <T> T fromFile(File file, Class<T> clazz) throws IOException {
        return OBJECT_MAPPER.readValue(file, clazz);
    }

    public static <T> T fromFile(File file, TypeReference<T> typeRef) throws IOException {
        return OBJECT_MAPPER.readValue(file, typeRef);
    }

    public static Map<String, Object> parseJsonLine(String line) {
        try {
            return OBJECT_MAPPER.readValue(line, new TypeReference<Map<String, Object>>() {});
        } catch (IOException e) {
            return null;
        }
    }

    public static boolean isValidJson(String str) {
        if (str == null || str.trim().isEmpty()) return false;
        try {
            OBJECT_MAPPER.readTree(str.trim());
            return true;
        } catch (IOException e) {
            return false;
        }
    }
}
