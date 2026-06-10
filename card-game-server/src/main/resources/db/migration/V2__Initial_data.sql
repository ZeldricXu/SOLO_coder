-- =============================================
-- Card Game Database Migration - Version 2
-- Initial Data
-- =============================================

-- 初始化一个测试玩家
INSERT IGNORE INTO player_profiles (
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
);

-- 初始化第一个赛季
INSERT IGNORE INTO seasons (
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
);

-- 初始化基础卡牌库
INSERT IGNORE INTO card_library (card_id, name, type, rarity, cost, damage, block, heal, description, upgraded, created_at) VALUES
('strike', '打击', 'ATTACK', 'BASIC', 1, 6, 0, 0, '造成6点伤害', 0, UNIX_TIMESTAMP() * 1000),
('defend', '防御', 'SKILL', 'BASIC', 1, 0, 5, 0, '获得5点格挡', 0, UNIX_TIMESTAMP() * 1000),
('bash', '重击', 'ATTACK', 'STARTER', 2, 8, 0, 0, '造成8点伤害，施加2层易伤', 0, UNIX_TIMESTAMP() * 1000),
('cleave', '顺劈斩', 'ATTACK', 'COMMON', 1, 8, 0, 0, '对所有敌人造成8点伤害', 0, UNIX_TIMESTAMP() * 1000),
('iron_wave', '铁浪', 'ATTACK', 'COMMON', 1, 5, 5, 0, '造成5点伤害，获得5点格挡', 0, UNIX_TIMESTAMP() * 1000),
('shrug_it_off', '耸肩', 'SKILL', 'COMMON', 1, 0, 8, 0, '获得8点格挡，抽1张牌', 0, UNIX_TIMESTAMP() * 1000),
('inflame', '燃烧', 'POWER', 'UNCOMMON', 1, 0, 0, 0, '获得2层力量', 0, UNIX_TIMESTAMP() * 1000),
('metallicize', '金属化', 'POWER', 'UNCOMMON', 1, 0, 3, 0, '每回合结束时获得3点格挡', 0, UNIX_TIMESTAMP() * 1000),
('reaper', '收割', 'ATTACK', 'RARE', 2, 4, 0, 0, '对所有敌人造成4点伤害，每击杀一个敌人恢复4点生命', 0, UNIX_TIMESTAMP() * 1000),
('demon_form', '恶魔形态', 'POWER', 'RARE', 3, 0, 0, 0, '每回合开始时获得2层力量', 0, UNIX_TIMESTAMP() * 1000),
('bludgeon', '重锤', 'ATTACK', 'RARE', 3, 32, 0, 0, '造成32点伤害', 0, UNIX_TIMESTAMP() * 1000),
('impervious', '无坚不摧', 'SKILL', 'RARE', 2, 0, 30, 0, '获得30点格挡。消耗', 0, UNIX_TIMESTAMP() * 1000),
('feed', '吞噬', 'ATTACK', 'RARE', 1, 10, 0, 0, '造成10点伤害。若击杀敌人，永久增加3点最大生命值。消耗', 0, UNIX_TIMESTAMP() * 1000),
('offering', '献祭', 'SKILL', 'RARE', 0, 0, 0, 6, '失去6点生命，获得2点能量，抽3张牌。消耗', 0, UNIX_TIMESTAMP() * 1000),
('corruption', '腐化', 'POWER', 'RARE', 3, 0, 0, 0, '技能牌消耗0点能量。打出技能牌后将其消耗', 0, UNIX_TIMESTAMP() * 1000),
('limit_break', '突破极限', 'SKILL', 'RARE', 1, 0, 0, 0, '力量翻倍。消耗', 0, UNIX_TIMESTAMP() * 1000);
