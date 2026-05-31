package com.datamasker.domain.audit.chain;

import com.datamasker.domain.audit.model.AuditLogEntry;
import com.datamasker.domain.audit.model.TamperDetectionResult;
import com.datamasker.infrastructure.crypto.CryptoUtils;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Component
public class HashChain {

    public String computeHash(AuditLogEntry entry) {
        String raw = entry.getPrevHash()
                + entry.getOperation()
                + entry.getOperator()
                + entry.getModule()
                + entry.getDetail()
                + entry.getTimestamp().toString();
        try {
            return CryptoUtils.sha256Hash(raw);
        } catch (Exception e) {
            throw new RuntimeException("Failed to compute hash", e);
        }
    }

    public String computeHashWithPrev(AuditLogEntry entry, String prevHash) {
        entry.setPrevHash(prevHash);
        return computeHash(entry);
    }

    public TamperDetectionResult verifyChain(List<AuditLogEntry> entries) {
        TamperDetectionResult result = new TamperDetectionResult();
        result.setTotalLogs(entries.size());
        result.setCheckedAt(LocalDateTime.now());

        List<Integer> tamperedIndices = new ArrayList<>();

        for (int i = 0; i < entries.size(); i++) {
            AuditLogEntry entry = entries.get(i);

            String recomputedHash = computeHash(entry);
            if (!recomputedHash.equals(entry.getLogHash())) {
                tamperedIndices.add(i);
                continue;
            }

            if (i == 0) {
                if (!getGenesisHash().equals(entry.getPrevHash())) {
                    tamperedIndices.add(i);
                }
            } else {
                String expectedPrevHash = entries.get(i - 1).getLogHash();
                if (!expectedPrevHash.equals(entry.getPrevHash())) {
                    tamperedIndices.add(i);
                }
            }
        }

        result.setTamperedIndices(tamperedIndices);
        result.setTamperedCount(tamperedIndices.size());
        result.setVerified(tamperedIndices.isEmpty());
        return result;
    }

    public String getGenesisHash() {
        return "GENESIS_HASH_000000000000000000000000000000000000000000000000000000000000";
    }
}
