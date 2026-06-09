-- =============================================
-- Card Game Database Schema
-- Multiplayer Roguelike Card Game Server
-- =============================================

CREATE DATABASE IF NOT EXISTS cardgame
    DEFAULT CHARACTER SET utf8mb4
    DEFAULT COLLATE utf8mb4_unicode_ci;

USE cardgame;

-- =============================================
-- Table: player_profiles
-- 玩家档案表
-- =============================================
DROP TABLE IF EXISTS player_profiles;
CREATE TABLE player_profiles (
    player_id VARCHAR(64) NOT NULL PRIMARY KEY COMMENT '玩家ID',
    username VARCHAR(64) NOT NULL UNIQUE COMMENT '用户名',
    nickname VARCHAR(64) COMMENT '昵称',
    level INT NOT NULL DEFAULT 1 COMMENT '等级',
    experience INT NOT NULL DEFAULT 0 COMMENT '经验值',
    total_play_time_seconds BIGINT NOT NULL DEFAULT 0 COMMENT '总游戏时间(秒)',
    total_games_played INT NOT NULL DEFAULT 0 COMMENT '总游戏次数',
    total_wins INT NOT NULL DEFAULT 0 COMMENT '总胜利次数',
    highest_floor_reached INT NOT NULL DEFAULT 0 COMMENT '最高到达层数',
    total_gold_earned BIGINT NOT NULL DEFAULT 0 COMMENT '总获得金币',
    unlocked_card_ids JSON COMMENT '已解锁的卡牌ID列表',
    achievements JSON COMMENT '成就数据',
    stats JSON COMMENT '统计数据',
    created_at BIGINT NOT NULL COMMENT '创建时间',
    last_login_at BIGINT COMMENT '最后登录时间',
    online TINYINT(1) NOT NULL DEFAULT 0 COMMENT '是否在线',
    current_save_id VARCHAR(64) COMMENT '当前存档ID',
    current_room_id VARCHAR(64) COMMENT '当前房间ID',
    INDEX idx_username (username),
    INDEX idx_online (online),
    INDEX idx_level (level)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='玩家档案表';

-- =============================================
-- Table: game_saves
-- 游戏存档表
-- =============================================
DROP TABLE IF EXISTS game_saves;
CREATE TABLE game_saves (
    save_id VARCHAR(64) NOT NULL PRIMARY KEY COMMENT '存档ID',
    room_id VARCHAR(64) NOT NULL COMMENT '房间ID',
    host_player_id VARCHAR(64) NOT NULL COMMENT '房主ID',
    player_ids JSON NOT NULL COMMENT '玩家ID列表',
    player_states JSON COMMENT '玩家状态数据',
    player_decks JSON COMMENT '玩家牌组数据',
    game_map JSON COMMENT '地图数据',
    current_floor INT NOT NULL DEFAULT 0 COMMENT '当前层数',
    score INT NOT NULL DEFAULT 0 COMMENT '分数',
    gold INT NOT NULL DEFAULT 0 COMMENT '金币',
    seed BIGINT NOT NULL COMMENT '地图种子',
    created_at BIGINT NOT NULL COMMENT '创建时间',
    updated_at BIGINT NOT NULL COMMENT '更新时间',
    play_time_seconds BIGINT NOT NULL DEFAULT 0 COMMENT '游戏时间(秒)',
    progress_data JSON COMMENT '进度数据',
    locked TINYINT(1) NOT NULL DEFAULT 0 COMMENT '是否锁定',
    locked_by VARCHAR(64) COMMENT '锁定者ID',
    locked_at BIGINT COMMENT '锁定时间',
    completed TINYINT(1) NOT NULL DEFAULT 0 COMMENT '是否完成',
    victory TINYINT(1) NOT NULL DEFAULT 0 COMMENT '是否胜利',
    difficulty VARCHAR(32) COMMENT '难度',
    version VARCHAR(32) COMMENT '版本号',
    INDEX idx_room_id (room_id),
    INDEX idx_host_player_id (host_player_id),
    INDEX idx_completed (completed),
    INDEX idx_updated_at (updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='游戏存档表';

-- =============================================
-- Table: battle_logs
-- 战斗日志表
-- =============================================
DROP TABLE IF EXISTS battle_logs;
CREATE TABLE battle_logs (
    battle_log_id VARCHAR(64) NOT NULL PRIMARY KEY COMMENT '战斗日志ID',
    battle_id VARCHAR(64) NOT NULL COMMENT '战斗ID',
    room_id VARCHAR(64) NOT NULL COMMENT '房间ID',
    save_id VARCHAR(64) COMMENT '存档ID',
    floor INT NOT NULL COMMENT '层数',
    seed BIGINT NOT NULL COMMENT '种子',
    initial_player_states JSON COMMENT '初始玩家状态',
    initial_enemy_states JSON COMMENT '初始敌人状态',
    actions JSON COMMENT '战斗动作序列',
    result ENUM('NOT_STARTED', 'PLAYER_TURN', 'ENEMY_TURN', 'VICTORY', 'DEFEAT', 'FLED') COMMENT '战斗结果',
    start_time BIGINT NOT NULL COMMENT '开始时间',
    end_time BIGINT COMMENT '结束时间',
    duration_ms BIGINT COMMENT '持续时间(毫秒)',
    total_turns INT NOT NULL DEFAULT 0 COMMENT '总回合数',
    total_rounds INT NOT NULL DEFAULT 0 COMMENT '总轮数',
    stats JSON COMMENT '战斗统计数据',
    version VARCHAR(32) COMMENT '版本号',
    INDEX idx_battle_id (battle_id),
    INDEX idx_room_id (room_id),
    INDEX idx_save_id (save_id),
    INDEX idx_result (result),
    INDEX idx_start_time (start_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='战斗日志表';

-- =============================================
-- Table: seasons
-- 赛季表
-- =============================================
DROP TABLE IF EXISTS seasons;
CREATE TABLE seasons (
    season_id VARCHAR(64) NOT NULL PRIMARY KEY COMMENT '赛季ID',
    name VARCHAR(128) NOT NULL COMMENT '赛季名称',
    description TEXT COMMENT '赛季描述',
    start_time BIGINT NOT NULL COMMENT '开始时间',
    end_time BIGINT NOT NULL COMMENT '结束时间',
    active TINYINT(1) NOT NULL DEFAULT 1 COMMENT '是否激活',
    rewards JSON COMMENT '奖励配置',
    reward_tiers JSON COMMENT '奖励等级',
    version VARCHAR(32) COMMENT '版本号',
    INDEX idx_active (active),
    INDEX idx_start_time (start_time),
    INDEX idx_end_time (end_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='赛季表';

-- =============================================
-- Table: daily_challenges
-- 每日挑战表
-- =============================================
DROP TABLE IF EXISTS daily_challenges;
CREATE TABLE daily_challenges (
    challenge_id VARCHAR(64) NOT NULL PRIMARY KEY COMMENT '挑战ID',
    date VARCHAR(16) NOT NULL UNIQUE COMMENT '日期(YYYY-MM-DD)',
    seed BIGINT NOT NULL COMMENT '种子',
    description VARCHAR(256) COMMENT '描述',
    difficulty VARCHAR(32) NOT NULL COMMENT '难度',
    target_floor INT NOT NULL COMMENT '目标层数',
    score_multiplier INT NOT NULL DEFAULT 1 COMMENT '分数倍率',
    modifiers JSON COMMENT '挑战修饰符',
    start_time BIGINT NOT NULL COMMENT '开始时间',
    end_time BIGINT NOT NULL COMMENT '结束时间',
    INDEX idx_date (date),
    INDEX idx_difficulty (difficulty)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='每日挑战表';

-- =============================================
-- Table: player_friends
-- 玩家好友表 (可选扩展)
-- =============================================
DROP TABLE IF EXISTS player_friends;
CREATE TABLE player_friends (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '自增ID',
    player_id VARCHAR(64) NOT NULL COMMENT '玩家ID',
    friend_id VARCHAR(64) NOT NULL COMMENT '好友ID',
    status TINYINT NOT NULL DEFAULT 0 COMMENT '状态: 0-申请中, 1-已接受, 2-已拒绝',
    created_at BIGINT NOT NULL COMMENT '创建时间',
    updated_at BIGINT COMMENT '更新时间',
    UNIQUE KEY uk_player_friend (player_id, friend_id),
    INDEX idx_player_id (player_id),
    INDEX idx_friend_id (friend_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='玩家好友表';

-- =============================================
-- Table: card_library
-- 卡牌库表 (可选扩展)
-- =============================================
DROP TABLE IF EXISTS card_library;
CREATE TABLE card_library (
    card_id VARCHAR(64) NOT NULL PRIMARY KEY COMMENT '卡牌ID',
    name VARCHAR(64) NOT NULL COMMENT '卡牌名称',
    type VARCHAR(32) NOT NULL COMMENT '卡牌类型',
    rarity VARCHAR(32) NOT NULL COMMENT '稀有度',
    cost INT NOT NULL COMMENT '能量消耗',
    damage INT DEFAULT 0 COMMENT '伤害',
    block INT DEFAULT 0 COMMENT '格挡',
    heal INT DEFAULT 0 COMMENT '治疗',
    effects JSON COMMENT '效果列表',
    description TEXT COMMENT '卡牌描述',
    upgraded TINYINT(1) DEFAULT 0 COMMENT '是否为升级版',
    upgraded_from VARCHAR(64) COMMENT '升级自哪个卡牌',
    unlock_requirement VARCHAR(256) COMMENT '解锁条件',
    created_at BIGINT NOT NULL COMMENT '创建时间',
    INDEX idx_type (type),
    INDEX idx_rarity (rarity),
    INDEX idx_cost (cost)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='卡牌库表';

-- =============================================
-- 初始化数据
-- =============================================

-- 初始化一个测试玩家
INSERT INTO player_profiles (
    player_id, username, nickname, level, experience,
    created_at, last_login_at, online
) VALUES (
    'test_player_001',
    'testuser',
    '测试玩家',
    1,
    0,
    UNIX_TIMESTAMP() * 1000,
    UNIX_TIMESTAMP() * 1000,
    0
) ON DUPLICATE KEY UPDATE player_id = player_id;

-- 初始化第一个赛季
INSERT INTO seasons (
    season_id, name, description, start_time, end_time,
    active, rewards, reward_tiers, version
) VALUES (
    'season_001',
    'Season 1 - Launch',
    '首发赛季，欢迎各位玩家！',
    UNIX_TIMESTAMP() * 1000,
    (UNIX_TIMESTAMP() + 30 * 24 * 3600) * 1000,
    1,
    '{"gold": 10000, "cards": 5}',
    '["Legendary Card Back", "Epic Card Pack", "Rare Card Pack", "Common Card Pack", "Gold Coins"]',
    '1.0'
) ON DUPLICATE KEY UPDATE season_id = season_id;

-- 初始化今日挑战
INSERT INTO daily_challenges (
    challenge_id, date, seed, description, difficulty,
    target_floor, score_multiplier, modifiers, start_time, end_time
) VALUES (
    'daily_' + DATE_FORMAT(NOW(), '%Y%m%d'),
    DATE_FORMAT(NOW(), '%Y-%m-%d'),
    123456789,
    'Conquer the dungeon with Normal difficulty!',
    'Normal',
    15,
    1,
    '{"modifier_0": "Extra Gold"}',
    UNIX_TIMESTAMP(CURDATE()) * 1000,
    (UNIX_TIMESTAMP(CURDATE()) + 24 * 3600) * 1000
) ON DUPLICATE KEY UPDATE challenge_id = challenge_id;

-- =============================================
-- View: player_rank_view
-- 玩家排行榜视图
-- =============================================
CREATE OR REPLACE VIEW player_rank_view AS
SELECT
    player_id,
    username,
    nickname,
    level,
    experience,
    total_wins,
    total_games_played,
    highest_floor_reached,
    CASE WHEN total_games_played > 0 THEN ROUND(total_wins / total_games_played * 100, 2) ELSE 0 END AS win_rate,
    total_play_time_seconds,
    total_gold_earned
FROM player_profiles
ORDER BY
    highest_floor_reached DESC,
    total_wins DESC,
    win_rate DESC;

-- =============================================
-- Stored Procedure: GetPlayerStats
-- 获取玩家统计数据
-- =============================================
DELIMITER //
DROP PROCEDURE IF EXISTS GetPlayerStats//
CREATE PROCEDURE GetPlayerStats(IN p_player_id VARCHAR(64))
BEGIN
    SELECT
        p.*,
        (SELECT COUNT(*) FROM game_saves s WHERE FIND_IN_SET(p_player_id, s.player_ids) > 0) AS total_games,
        (SELECT COUNT(*) FROM game_saves s WHERE FIND_IN_SET(p_player_id, s.player_ids) > 0 AND s.victory = 1) AS total_victories,
        (SELECT MAX(current_floor) FROM game_saves s WHERE FIND_IN_SET(p_player_id, s.player_ids) > 0) AS best_floor
    FROM player_profiles p
    WHERE p.player_id = p_player_id;
END //
DELIMITER ;

-- =============================================
-- Stored Procedure: GetTopPlayers
-- 获取排行榜前N名玩家
-- =============================================
DELIMITER //
DROP PROCEDURE IF EXISTS GetTopPlayers//
CREATE PROCEDURE GetTopPlayers(IN limit_count INT)
BEGIN
    SELECT
        player_id,
        username,
        nickname,
        level,
        highest_floor_reached,
        total_wins,
        total_games_played,
        CASE WHEN total_games_played > 0 THEN ROUND(total_wins / total_games_played * 100, 2) ELSE 0 END AS win_rate,
        @rank := @rank + 1 AS player_rank
    FROM player_profiles, (SELECT @rank := 0) r
    ORDER BY
        highest_floor_reached DESC,
        total_wins DESC,
        win_rate DESC
    LIMIT limit_count;
END //
DELIMITER ;

SHOW TABLES;
