package com.edgescheduler.ota.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.edgescheduler.ota.dto.DiffPackageRequest;
import com.edgescheduler.ota.dto.FirmwareDTO;
import com.edgescheduler.ota.dto.OtaJobDTO;
import com.edgescheduler.ota.entity.Firmware;
import com.edgescheduler.ota.entity.OtaDeviceUpgrade;
import com.edgescheduler.ota.entity.OtaJob;

import java.util.List;
import java.util.Map;

public interface OtaService {

    FirmwareDTO createFirmware(FirmwareDTO firmwareDTO);

    FirmwareDTO getFirmware(String firmwareId);

    IPage<FirmwareDTO> listFirmwares(Page<Firmware> page, String productKey, String status);

    FirmwareDTO publishFirmware(String firmwareId);

    FirmwareDTO retireFirmware(String firmwareId);

    void deleteFirmware(String firmwareId);

    Map<String, Object> generateDiffPackage(DiffPackageRequest request);

    OtaJobDTO createOtaJob(OtaJobDTO otaJobDTO);

    OtaJobDTO getOtaJob(String jobId);

    IPage<OtaJobDTO> listOtaJobs(Page<OtaJob> page, String productKey, String status);

    OtaJobDTO startOtaJob(String jobId);

    OtaJobDTO pauseOtaJob(String jobId);

    OtaJobDTO cancelOtaJob(String jobId);

    OtaJobDTO rollbackOtaJob(String jobId);

    void processGrayScaleBatches();

    OtaDeviceUpgrade updateDeviceUpgradeProgress(String jobId, String deviceKey, String status,
                                                  Integer progress, String errorCode, String errorMessage);

    List<OtaDeviceUpgrade> getDeviceUpgrades(String jobId);

    OtaDeviceUpgrade getDeviceUpgrade(String jobId, String deviceKey);

    Map<String, Object> getOtaJobStatistics(String jobId);
}
