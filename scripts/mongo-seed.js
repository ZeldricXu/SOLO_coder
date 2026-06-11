/* ============================================================
 * mongo-seed.js — run on every 'mongo-seed' container start
 *   (idempotent via upsert, safe to re-run)
 * ============================================================
 */

const dbName = 'gameroom';
const db = db.getSiblingDB(dbName);

/* -------- Test Accounts -------- */
const testAccounts = [
  {
    user_id: 'test_player_001',
    nickname: '小明（测试）',
    avatar: 'avatar_001.png',
    level: 15,
    gender: 'male',
    coins: 100000,
    diamonds: 9999,
    vip_level: 3,
    created_at: new Date(),
    updated_at: new Date(),
  },
  {
    user_id: 'test_player_002',
    nickname: '小红（测试）',
    avatar: 'avatar_002.png',
    level: 22,
    gender: 'female',
    coins: 58000,
    diamonds: 1200,
    vip_level: 1,
    created_at: new Date(),
    updated_at: new Date(),
  },
  {
    user_id: 'test_player_003',
    nickname: '小刚（测试）',
    avatar: 'avatar_003.png',
    level: 8,
    gender: 'male',
    coins: 32000,
    diamonds: 0,
    vip_level: 0,
    created_at: new Date(),
    updated_at: new Date(),
  },
  {
    user_id: 'test_observer_001',
    nickname: '路人甲（观战）',
    avatar: 'avatar_obs_1.png',
    level: 5,
    gender: 'unknown',
    coins: 1000,
    diamonds: 0,
    vip_level: 0,
    created_at: new Date(),
    updated_at: new Date(),
  },
];

let inserted = 0;
let updated = 0;
testAccounts.forEach((doc) => {
  const res = db.accounts.updateOne(
    { user_id: doc.user_id },
    { $setOnInsert: doc },
    { upsert: true },
  );
  if (res.upsertedCount > 0) inserted += res.upsertedCount;
  if (res.modifiedCount > 0) updated += res.modifiedCount;
});
print(`[mongo-seed] accounts: ${inserted} inserted, ${updated} updated, total ${testAccounts.length}`);

/* -------- Player Stats (ELO baseline for testing) -------- */
const statsSeeds = [
  { user_id: 'test_player_001', game_type: 1, nick: '小明', elo: 1520, played: 128, wins: 72, win_rate: 0.56, score_delta: 15_800 },
  { user_id: 'test_player_001', game_type: 2, nick: '小明', elo: 1410, played: 42,  wins: 22, win_rate: 0.52, score_delta: 2_100  },
  { user_id: 'test_player_002', game_type: 1, nick: '小红', elo: 1680, played: 210, wins: 138, win_rate: 0.66, score_delta: 48_300 },
  { user_id: 'test_player_002', game_type: 2, nick: '小红', elo: 1350, played: 18,  wins: 9,  win_rate: 0.50, score_delta: -400   },
  { user_id: 'test_player_003', game_type: 1, nick: '小刚', elo: 1240, played: 60,  wins: 25, win_rate: 0.42, score_delta: -12_600 },
];

let sIn = 0;
statsSeeds.forEach((doc) => {
  doc.created_at = new Date();
  doc.updated_at = new Date();
  doc.best_win_streak = doc.played > 30 ? 7 : 3;
  doc.max_win_streak = doc.best_win_streak;
  const res = db.player_stats.updateOne(
    { user_id: doc.user_id, game_type: doc.game_type },
    { $setOnInsert: doc },
    { upsert: true },
  );
  if (res.upsertedCount > 0) sIn += res.upsertedCount;
});
print(`[mongo-seed] player_stats: ${sIn} inserted`);

/* -------- Bot/Robot Accounts -------- */
for (let i = 1; i <= 20; i++) {
  const idx = String(i).padStart(3, '0');
  const nicknames = ['小明', '小红', '小刚', '小丽', '老王', '阿强', '阿梅', '阿呆'];
  const robot = {
    user_id: `robot_${idx}`,
    nickname: `AI-${nicknames[i % nicknames.length]}(${idx})`,
    avatar: `robot_${(i % 4) + 1}.png`,
    level: 1 + (i % 30),
    gender: i % 2 === 0 ? 'female' : 'male',
    coins: 10000 + i * 500,
    diamonds: i % 5 === 0 ? 100 : 0,
    vip_level: 0,
    is_robot: true,
    created_at: new Date(),
    updated_at: new Date(),
  };
  db.accounts.updateOne(
    { user_id: robot.user_id },
    { $setOnInsert: robot },
    { upsert: true },
  );
}
print('[mongo-seed] robot accounts: 20 upserted');

print('[mongo-seed] All seed data processed ✓');
