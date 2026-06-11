/* ============================================================
 * mongo-init.js — runs on FIRST launch when /data/db is empty.
 *   Creates the recommended indexes used by the game engine.
 *   NOTE: This script runs BEFORE authentication is configured.
 * ============================================================
 */

const dbName = 'gameroom';
const db = db.getSiblingDB(dbName);

/* -------- rooms -------- */
db.rooms.createIndex({ 'host_id': 1 }, { name: 'idx_rooms_host' });
db.rooms.createIndex({ 'state': 1 }, { name: 'idx_rooms_state' });
db.rooms.createIndex({ 'players.user_id': 1 }, { name: 'idx_rooms_player' });
db.rooms.createIndex({ 'config.invite_code': 1 }, { unique: true, sparse: true, name: 'idx_rooms_invite_code' });
db.rooms.createIndex({ 'game_type': 1, 'state': 1 }, { name: 'idx_rooms_game_state' });
db.rooms.createIndex({ 'created_at': -1 }, { expireAfterSeconds: 60 * 60 * 24 * 7, name: 'ttl_rooms_7d' });

/* -------- player_stats -------- */
db.player_stats.createIndex({ 'user_id': 1, 'game_type': 1 }, { unique: true, name: 'idx_player_stats_user_game' });
db.player_stats.createIndex({ 'game_type': 1, 'elo': -1 }, { name: 'idx_player_stats_leaderboard' });
db.player_stats.createIndex({ 'updated_at': -1 }, { name: 'idx_player_stats_updated' });

/* -------- settlements -------- */
db.settlements.createIndex({ 'room_id': 1 }, { name: 'idx_settlements_room' });
db.settlements.createIndex({ 'players.user_id': 1, 'created_at': -1 }, { name: 'idx_settlements_user_time' });
db.settlements.createIndex({ 'game_type': 1, 'created_at': -1 }, { name: 'idx_settlements_game_time' });

/* -------- daily_stats -------- */
db.daily_stats.createIndex({ 'date': 1, 'user_id': 1, 'game_type': 1 }, { unique: true, name: 'idx_daily_stats_composite' });
db.daily_stats.createIndex({ 'date': 1 }, { name: 'idx_daily_stats_date' });

/* -------- highlights -------- */
db.highlights.createIndex({ 'user_id': 1, 'created_at': -1 }, { name: 'idx_highlights_user_time' });
db.highlights.createIndex({ 'room_id': 1 }, { name: 'idx_highlights_room' });
db.highlights.createIndex({ 'type': 1, 'game_type': 1 }, { name: 'idx_highlights_type_game' });

/* -------- playbacks -------- */
db.playbacks.createIndex({ 'room_id': 1 }, { unique: true, name: 'idx_playbacks_room' });
db.playbacks.createIndex({ 'created_at': -1 }, { expireAfterSeconds: 60 * 60 * 24 * 30, name: 'ttl_playbacks_30d' });

/* -------- interaction -------- */
db.danmaku.createIndex({ 'room_id': 1, 'ts': 1 }, { name: 'idx_danmaku_room_ts' });
db.danmaku.createIndex({ 'ts': -1 }, { expireAfterSeconds: 60 * 60 * 24 * 7, name: 'ttl_danmaku_7d' });

db.gifts.createIndex({ 'room_id': 1, 'sent_at': -1 }, { name: 'idx_gifts_room_time' });
db.gifts.createIndex({ 'from_user_id': 1, 'sent_at': -1 }, { name: 'idx_gifts_from' });

db.observer_bets.createIndex({ 'room_id': 1, 'user_id': 1 }, { unique: true, name: 'idx_bets_room_user' });

print(`[mongo-init] Created indexes on '${dbName}' successfully`);
