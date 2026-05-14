package com.datasync.service.version;

import com.datasync.model.DataVersion;

import java.util.List;
import java.util.Optional;

public interface VersionManager {

    DataVersion saveVersion(DataVersion version);

    Optional<DataVersion> getVersion(String dataSource, String dataKey);

    List<DataVersion> getVersions(String dataSource);

    boolean deleteVersion(String dataSource, String dataKey);

    String generateVersion(Map<String, Object> data);

    String calculateChecksum(Object data);

    boolean compareVersions(DataVersion source, DataVersion target);

    void updateVersion(String dataSource, String dataKey, String version, String checksum);
}
