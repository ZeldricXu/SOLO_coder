package com.cardgame.rank.service;

import com.cardgame.common.utils.IdGenerator;
import com.cardgame.rank.entity.Season;
import com.cardgame.rank.mapper.SeasonMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

@Slf4j
@Component
public class SeasonService {

    @Autowired
    private SeasonMapper seasonMapper;

    @Autowired
    private RankService rankService;

    @Autowired
    private com.cardgame.save.service.SaveService saveService;

    private static final long SEASON_DURATION_MS = 30L * 24 * 60 * 60 * 1000;

    @Transactional
    public Season createNewSeason(String name, String description) {
        Season currentSeason = getCurrentSeason();
        if (currentSeason != null && currentSeason.isInProgress()) {
            endSeason(currentSeason);
        }

        long startTime = System.currentTimeMillis();
        long endTime = startTime + SEASON_DURATION_MS;

        Season season = Season.builder()
                .seasonId(IdGenerator.generateUUID())
                .name(name)
                .description(description)
                .startTime(startTime)
                .endTime(endTime)
                .active(true)
                .version("1.0")
                .build();

        season.getRewardTiers().add("Legendary Card Back");
        season.getRewardTiers().add("Epic Card Pack");
        season.getRewardTiers().add("Rare Card Pack");
        season.getRewardTiers().add("Common Card Pack");
        season.getRewardTiers().add("Gold Coins");

        seasonMapper.insertSeason(season);
        log.info("Created new season: {} ({})", name, season.getSeasonId());

        return season;
    }

    public Season getCurrentSeason() {
        return seasonMapper.findActiveSeason();
    }

    public Season getSeason(String seasonId) {
        return seasonMapper.findSeasonById(seasonId);
    }

    public List<Season> getAllSeasons() {
        return seasonMapper.findAllSeasons();
    }

    @Transactional
    public void endSeason(Season season) {
        season.setActive(false);
        season.setEndTime(System.currentTimeMillis());
        seasonMapper.updateSeason(season);

        distributeSeasonRewards(season);
        rankService.resetSeasonRank(season.getSeasonId());

        log.info("Ended season: {} ({})", season.getName(), season.getSeasonId());
    }

    private void distributeSeasonRewards(Season season) {
        List<com.cardgame.rank.entity.RankEntry> topPlayers = rankService.getSeasonRank(season.getSeasonId(), 0, 1000);

        for (com.cardgame.rank.entity.RankEntry entry : topPlayers) {
            String reward = season.getRewardForRank(entry.getRank());
            grantReward(entry.getPlayerId(), reward, entry.getRank());
            log.info("Distributed reward '{}' to player {} (rank {})",
                    reward, entry.getPlayerId(), entry.getRank());
        }
    }

    private void grantReward(String playerId, String rewardType, int rank) {
        com.cardgame.save.entity.PlayerProfile profile = saveService.getPlayerProfile(playerId);
        if (profile == null) return;

        switch (rewardType) {
            case "Legendary Card Back":
                profile.addStat("legendary_card_backs", 1);
                break;
            case "Epic Card Pack":
                profile.addStat("epic_card_packs", 1);
                break;
            case "Rare Card Pack":
                profile.addStat("rare_card_packs", 1);
                break;
            case "Common Card Pack":
                profile.addStat("common_card_packs", 1);
                break;
            case "Gold Coins":
                profile.addStat("season_gold_reward", 100 + (1000 - Math.min(rank, 1000)) / 10);
                break;
        }

        saveService.updatePlayerProfile(profile);
    }

    public void checkSeasonEnd() {
        Season currentSeason = getCurrentSeason();
        if (currentSeason != null && currentSeason.hasEnded()) {
            log.info("Season {} has ended, triggering end process", currentSeason.getName());
            endSeason(currentSeason);
        }
    }

    public long getTimeUntilNextSeason() {
        Season current = getCurrentSeason();
        if (current == null) {
            return 0;
        }
        return Math.max(0, current.getEndTime() - System.currentTimeMillis());
    }

    @Transactional
    public Season createAutomaticSeason() {
        LocalDateTime now = LocalDateTime.now();
        int month = now.getMonthValue();
        int year = now.getYear();

        String seasonName = String.format("Season %d-%02d", year, month);
        String description = String.format("Monthly season for %d/%02d", year, month);

        return createNewSeason(seasonName, description);
    }

    @Transactional
    public void recordSeasonScore(String playerId, int score, int floor) {
        Season current = getCurrentSeason();
        if (current != null) {
            rankService.updateSeasonScore(current.getSeasonId(), playerId, score);
        }
    }

    public long getSeasonStartTimeMillis(int year, int month) {
        LocalDateTime dateTime = LocalDateTime.of(year, month, 1, 0, 0, 0);
        return dateTime.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
    }
}
