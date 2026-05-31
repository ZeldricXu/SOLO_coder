package com.didauth.module.hdwallet.enhanced;

import lombok.Data;

import java.io.Serializable;
import java.util.List;

@Data
public class BatchAddressBookRequest implements Serializable {

    private List<BatchAddressBookEntry> entries;
    private String userId;

    @Data
    public static class BatchAddressBookEntry implements Serializable {
        private String address;
        private String chainType;
        private String name;
        private String label;
        private List<String> tags;
        private Boolean isWhitelist = false;
        private Boolean isBlacklist = false;
    }
}
