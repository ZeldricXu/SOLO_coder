package com.assetmanage.service;

import com.assetmanage.common.IdGenerator;
import com.assetmanage.entity.AssetStatistic;
import com.assetmanage.enums.AssetStatus;
import com.assetmanage.repository.AssetStatisticRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class AnalysisService {

    private final AssetStatisticRepository statisticRepository;
    private final AssetService assetService;

    @Transactional
    public void updateStatistics() {
        LocalDate today = LocalDate.now();
        Optional<AssetStatistic> existingOpt = statisticRepository.findByStatDate(today);
        
        AssetStatistic statistic;
        if (existingOpt.isPresent()) {
            statistic = existingOpt.get();
        } else {
            statistic = new AssetStatistic();
            statistic.setStatId(IdGenerator.generateStatId());
        }

        List<com.assetmanage.entity.Asset> allAssets = assetService.getAllAssets();
        long total = allAssets.size();
        long inUse = assetService.countByStatus(AssetStatus.IN_USE.getCode());
        long idle = assetService.countByStatus(AssetStatus.IDLE.getCode());
        long maintenance = assetService.countByStatus(AssetStatus.MAINTENANCE.getCode());
        long scraped = assetService.countByStatus(AssetStatus.SCRAPPED.getCode());
        BigDecimal totalValue = assetService.sumCurrentValue();

        statistic.setTotalAssets((int) total);
        statistic.setInUseAssets((int) inUse);
        statistic.setIdleAssets((int) idle);
        statistic.setMaintenanceAssets((int) maintenance);
        statistic.setScrapedAssets((int) scraped);
        statistic.setTotalValue(totalValue);

        statisticRepository.save(statistic);
        log.info("资产统计更新完成: total={}, inUse={}, idle={}, maintenance={}, scraped={}",
                total, inUse, idle, maintenance, scraped);
    }

    public AssetStatistic getTodayStatistics() {
        updateStatistics();
        return statisticRepository.findByStatDate(LocalDate.now()).orElse(null);
    }

    public List<AssetStatistic> getStatisticsByDateRange(LocalDate start, LocalDate end) {
        return statisticRepository.findByStatDateBetween(start, end);
    }

    public List<AssetStatistic> getAllStatistics() {
        return statisticRepository.findAllByOrderByStatDateDesc();
    }

    public java.util.Map<String, Object> generateReport() {
        AssetStatistic stat = getTodayStatistics();
        java.util.Map<String, Object> report = new java.util.HashMap<>();
        if (stat != null) {
            report.put("statDate", stat.getStatDate());
            report.put("totalAssets", stat.getTotalAssets());
            report.put("inUseAssets", stat.getInUseAssets());
            report.put("idleAssets", stat.getIdleAssets());
            report.put("maintenanceAssets", stat.getMaintenanceAssets());
            report.put("scrapedAssets", stat.getScrapedAssets());
            report.put("totalValue", stat.getTotalValue());
            
            if (stat.getTotalAssets() > 0) {
                report.put("utilizationRate", 
                        BigDecimal.valueOf(stat.getInUseAssets())
                                .divide(BigDecimal.valueOf(stat.getTotalAssets()), 4, java.math.RoundingMode.HALF_UP));
            }
        }
        return report;
    }
}
