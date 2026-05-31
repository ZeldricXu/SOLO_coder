package com.apishield.security.keysharding.api;

public interface ShareManagement {
    void deactivateShare(String shareId);
    void deleteSharesByKeyId(String keyId);
    void rotateShares(String keyId, String newSecret);
}
