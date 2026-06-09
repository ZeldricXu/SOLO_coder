package com.cardgame.save.service;

import com.cardgame.common.entity.Player;
import com.cardgame.common.utils.IdGenerator;
import com.cardgame.save.entity.GameSave;
import com.cardgame.save.entity.PlayerProfile;
import com.cardgame.save.mapper.SaveMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Slf4j
@Component
public class SaveService {

    @Autowired
    private SaveMapper saveMapper;

    @Transactional
    public GameSave createSave(String roomId, String hostPlayerId, List<Player> players) {
        GameSave save = GameSave.builder()
                .saveId(IdGenerator.generateUUID())
                .roomId(roomId)
                .hostPlayerId(hostPlayerId)
                .createdAt(System.currentTimeMillis())
                .updatedAt(System.currentTimeMillis())
                .version("1.0")
                .build();

        for (Player player : players) {
            save.addPlayer(player, player.getMasterDeck());
        }

        saveMapper.insertGameSave(save);
        log.info("Created save {} for room {}", save.getSaveId(), roomId);
        return save;
    }

    @Transactional
    public void updateSave(GameSave save) {
        save.setUpdatedAt(System.currentTimeMillis());
        saveMapper.updateGameSave(save);
        log.debug("Updated save {}", save.getSaveId());
    }

    public GameSave getSave(String saveId) {
        return saveMapper.findGameSaveById(saveId);
    }

    public GameSave getLatestSaveForRoom(String roomId) {
        return saveMapper.findLatestSaveByRoomId(roomId);
    }

    public List<GameSave> getPlayerSaves(String playerId) {
        return saveMapper.findSavesByPlayerId(playerId);
    }

    @Transactional
    public boolean lockSave(String saveId, String playerId) {
        int rows = saveMapper.lockSave(saveId, playerId, System.currentTimeMillis());
        if (rows > 0) {
            log.info("Save {} locked by player {}", saveId, playerId);
            return true;
        }
        log.warn("Failed to lock save {} by player {}", saveId, playerId);
        return false;
    }

    @Transactional
    public void unlockSave(String saveId) {
        saveMapper.unlockSave(saveId);
        log.info("Save {} unlocked", saveId);
    }

    @Transactional
    public void updatePlayerState(String saveId, Player player) {
        GameSave save = getSave(saveId);
        if (save != null) {
            save.updatePlayer(player);
            updateSave(save);
        }
    }

    @Transactional
    public PlayerProfile createPlayerProfile(String playerId, String username, String nickname) {
        PlayerProfile profile = PlayerProfile.builder()
                .playerId(playerId)
                .username(username)
                .nickname(nickname)
                .level(1)
                .experience(0)
                .createdAt(System.currentTimeMillis())
                .lastLoginAt(System.currentTimeMillis())
                .build();
        saveMapper.insertPlayerProfile(profile);
        log.info("Created player profile for {}", username);
        return profile;
    }

    @Transactional
    public void updatePlayerProfile(PlayerProfile profile) {
        profile.setLastLoginAt(System.currentTimeMillis());
        saveMapper.updatePlayerProfile(profile);
    }

    public PlayerProfile getPlayerProfile(String playerId) {
        return saveMapper.findPlayerProfileById(playerId);
    }

    public PlayerProfile getPlayerProfileByUsername(String username) {
        return saveMapper.findPlayerProfileByUsername(username);
    }

    @Transactional
    public void updatePlayerOnlineStatus(String playerId, boolean online) {
        PlayerProfile profile = getPlayerProfile(playerId);
        if (profile != null) {
            profile.setOnline(online);
            if (online) {
                profile.setLastLoginAt(System.currentTimeMillis());
            }
            updatePlayerProfile(profile);
        }
    }

    @Transactional
    public void addExperience(String playerId, int experience) {
        PlayerProfile profile = getPlayerProfile(playerId);
        if (profile != null) {
            profile.addExperience(experience);
            updatePlayerProfile(profile);
        }
    }

    @Transactional
    public void recordGameResult(String playerId, boolean won, int floorReached) {
        PlayerProfile profile = getPlayerProfile(playerId);
        if (profile != null) {
            profile.incrementGamesPlayed(won);
            profile.updateHighestFloor(floorReached);
            updatePlayerProfile(profile);
        }
    }

    @Transactional
    public void completeSave(String saveId, boolean victory, int score, String playerId) {
        GameSave save = getSave(saveId);
        if (save != null) {
            save.setCompleted(true);
            save.setVictory(victory);
            save.setScore(score);
            save.setUpdatedAt(System.currentTimeMillis());
            updateSave(save);

            for (String pid : save.getPlayerIds()) {
                recordGameResult(pid, victory, save.getCurrentFloor());
                if (victory) {
                    addExperience(pid, 100 + save.getCurrentFloor() * 10);
                }
            }
            unlockSave(saveId);
        }
    }
}
