package com.parking.platform.storage.entity;

import com.parking.platform.common.entity.BaseEntity;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

public class StoredObject extends BaseEntity {

    private String bucket;
    private String key;
    private String contentType;
    private Long size;
    private String provider;
    private String etag;
    private Map<String, String> metadata;
    private Instant expiresAt;

    public StoredObject() {
        super();
        this.metadata = new HashMap<>();
    }

    @Override
    protected String getIdPrefix() { return "obj"; }

    @Override
    public Map<String, Object> toMap() {
        Map<String, Object> map = super.toMap();
        map.put("bucket", bucket);
        map.put("key", key);
        map.put("contentType", contentType);
        map.put("size", size);
        map.put("provider", provider);
        map.put("etag", etag);
        map.put("metadata", metadata);
        map.put("expiresAt", expiresAt);
        return map;
    }

    public String getBucket() { return bucket; }
    public void setBucket(String bucket) { this.bucket = bucket; }
    public String getKey() { return key; }
    public void setKey(String key) { this.key = key; }
    public String getContentType() { return contentType; }
    public void setContentType(String contentType) { this.contentType = contentType; }
    public Long getSize() { return size; }
    public void setSize(Long size) { this.size = size; }
    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }
    public String getEtag() { return etag; }
    public void setEtag(String etag) { this.etag = etag; }
    public Map<String, String> getMetadata() { return metadata; }
    public void setMetadata(Map<String, String> metadata) { this.metadata = metadata; }
    public Instant getExpiresAt() { return expiresAt; }
    public void setExpiresAt(Instant expiresAt) { this.expiresAt = expiresAt; }
}
