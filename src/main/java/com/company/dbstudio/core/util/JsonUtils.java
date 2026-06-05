package com.company.dbstudio.core.util;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

public final class JsonUtils {

    private static final Logger logger = LoggerFactory.getLogger(JsonUtils.class);
    private static final ObjectMapper objectMapper;

    static {
        objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        objectMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        objectMapper.configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false);
        objectMapper.configure(SerializationFeature.INDENT_OUTPUT, true);
    }

    private JsonUtils() {
    }

    public static ObjectMapper getObjectMapper() {
        return objectMapper;
    }

    public static <T> String toJson(T obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            logger.error("Failed to serialize object to JSON", e);
            throw new RuntimeException("Failed to serialize object to JSON", e);
        }
    }

    public static <T> String toJsonPretty(T obj) {
        try {
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            logger.error("Failed to serialize object to pretty JSON", e);
            throw new RuntimeException("Failed to serialize object to pretty JSON", e);
        }
    }

    public static <T> T fromJson(String json, Class<T> clazz) {
        try {
            return objectMapper.readValue(json, clazz);
        } catch (JsonProcessingException e) {
            logger.error("Failed to deserialize JSON to object", e);
            throw new RuntimeException("Failed to deserialize JSON to object", e);
        }
    }

    public static <T> T fromJson(String json, TypeReference<T> typeRef) {
        try {
            return objectMapper.readValue(json, typeRef);
        } catch (JsonProcessingException e) {
            logger.error("Failed to deserialize JSON to object", e);
            throw new RuntimeException("Failed to deserialize JSON to object", e);
        }
    }

    public static <T> T fromJson(InputStream inputStream, Class<T> clazz) {
        try {
            return objectMapper.readValue(inputStream, clazz);
        } catch (IOException e) {
            logger.error("Failed to deserialize JSON from stream", e);
            throw new RuntimeException("Failed to deserialize JSON from stream", e);
        }
    }

    public static <T> T fromJson(InputStream inputStream, TypeReference<T> typeRef) {
        try {
            return objectMapper.readValue(inputStream, typeRef);
        } catch (IOException e) {
            logger.error("Failed to deserialize JSON from stream", e);
            throw new RuntimeException("Failed to deserialize JSON from stream", e);
        }
    }

    public static <T> T fromJson(File file, Class<T> clazz) {
        try {
            return objectMapper.readValue(file, clazz);
        } catch (IOException e) {
            logger.error("Failed to deserialize JSON from file", e);
            throw new RuntimeException("Failed to deserialize JSON from file", e);
        }
    }

    public static <T> void toJsonFile(T obj, File file) {
        try {
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(file, obj);
        } catch (IOException e) {
            logger.error("Failed to write JSON to file", e);
            throw new RuntimeException("Failed to write JSON to file", e);
        }
    }

    public static <T> void toJsonStream(T obj, OutputStream outputStream) {
        try {
            objectMapper.writeValue(outputStream, obj);
        } catch (IOException e) {
            logger.error("Failed to write JSON to stream", e);
            throw new RuntimeException("Failed to write JSON to stream", e);
        }
    }

    public static boolean isValidJson(String json) {
        if (StringUtils.isBlank(json)) {
            return false;
        }
        try {
            objectMapper.readTree(json);
            return true;
        } catch (JsonProcessingException e) {
            return false;
        }
    }

    public static String formatJson(String json) {
        try {
            return objectMapper.writerWithDefaultPrettyPrinter()
                    .writeValueAsString(objectMapper.readTree(json));
        } catch (JsonProcessingException e) {
            return json;
        }
    }
}
