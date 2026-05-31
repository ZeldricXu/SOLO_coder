package com.web3platform.storageadapter.util;

import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;

import java.util.concurrent.TimeUnit;

public final class HttpClientFactory {

    private static final OkHttpClient SHARED_HTTP_CLIENT = new OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .connectionPool(new okhttp3.ConnectionPool(20, 5, TimeUnit.MINUTES))
            .build();

    private static final ObjectMapper SHARED_OBJECT_MAPPER = new ObjectMapper();

    private static final MediaType OCTET_STREAM = MediaType.parse("application/octet-stream");
    private static final MediaType JSON = MediaType.parse("application/json");
    private static final okhttp3.RequestBody EMPTY_BODY = okhttp3.RequestBody.create(new byte[0], OCTET_STREAM);

    private HttpClientFactory() {}

    public static OkHttpClient getSharedHttpClient() {
        return SHARED_HTTP_CLIENT;
    }

    public static ObjectMapper getSharedObjectMapper() {
        return SHARED_OBJECT_MAPPER;
    }

    public static MediaType octetStream() {
        return OCTET_STREAM;
    }

    public static MediaType json() {
        return JSON;
    }

    public static okhttp3.RequestBody emptyBody() {
        return EMPTY_BODY;
    }

    public static String normalizeBaseUrl(String url) {
        if (url == null || url.isEmpty()) {
            return url;
        }
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
