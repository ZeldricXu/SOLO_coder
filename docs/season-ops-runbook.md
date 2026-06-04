# 跨服争霸赛 赛季运维手册

## 一、创建新赛季

### 1.1 数据库初始化

```sql
-- 1. 创建新赛季记录
INSERT INTO season (name, status, start_time, end_time, max_players_per_server, rules_json)
VALUES (
    '第二赛季·巅峰之战',
    'PREPARING',
    '2026-07-01 10:00:00',
    '2026-07-14 22:00:00',
    100,
    '{"bracketSize":200,"killBase":100,"captureBase":200,"streakThreshold":3}'
);

-- 2. 记录赛季ID（后续步骤需要）
SET @season_id = LAST_INSERT_ID();

-- 3. 创建赛季专属排行榜快照表（按需）
CREATE TABLE IF NOT EXISTS season_ranking_snapshot_s2
SELECT * FROM season_ranking WHERE 1=0;

-- 4. 重置Redis排行榜（在应用启动后通过管理接口执行）
-- 见下方管理后台操作
```

### 1.2 管理后台操作步骤

| 步骤 | 操作 | 说明 |
|------|------|------|
| 1 | 登录管理后台 `https://battle.example.com/admin` | 使用运维账号 |
| 2 | 进入「赛季管理」→「创建新赛季」 | |
| 3 | 填写赛季名称、起止时间、每服名额 | 对应 `season` 表字段 |
| 4 | 配置匹配参数：分档大小、等待超时、权重 | 对应 `battle.matching.*` 配置 |
| 5 | 配置积分参数：击杀/助攻/占点/连杀 | 对应 `battle.score.*` 配置 |
| 6 | 配置奖励梯度：传说/史诗/普通/基础/最低 | 对应奖励模板 |
| 7 | 点击「保存并预览」→ 确认无误后「发布」 | 状态从 PREPARING → READY |
| 8 | 赛季开始时间到达后，系统自动切换为 ACTIVE | 由 `@Scheduled` 任务驱动 |

### 1.3 赛季开始前检查清单

- [ ] 数据库 `season` 表记录已插入且状态为 `READY`
- [ ] Redis 排行榜 key 已清空（`leaderboard:score` / `leaderboard:kills` / `leaderboard:guild`）
- [ ] K8s ConfigMap 中赛季相关配置已更新并 rollout
- [ ] Harbor 镜像版本与部署版本一致
- [ ] InfluxDB bucket 已创建且写入权限正常
- [ ] 监控面板（Grafana）赛季看板已就绪
- [ ] 奖励模板已配置（传说/史诗/普通/基础/最低五档内容）
- [ ] 反作弊规则阈值已根据上赛季数据调优
- [ ] 回放存储目录 `/data/replays` 磁盘空间充足（预估2TB/赛季）
- [ ] 数据库连接池参数已根据预估并发量调整

### 1.4 赛季激活

```bash
# 通过管理API激活赛季（或等待定时任务自动激活）
curl -X POST https://battle.example.com/api/admin/seasons/{seasonId}/activate \
  -H "Authorization: Bearer $ADMIN_TOKEN"

# 验证赛季状态
curl https://battle.example.com/api/admin/seasons/{seasonId} \
  -H "Authorization: Bearer $ADMIN_TOKEN"
# 期望: {"status": "ACTIVE", ...}

# 验证Redis排行榜已初始化
kubectl exec -it redis-prod-0 -n battle-platform-prod -- redis-cli ZCARD leaderboard:score
# 期望: 0（空排行榜，等待玩家报名）
```

---

## 二、赛季期间监控

### 2.1 核心监控指标

| 指标 | 采集源 | 告警阈值 | 告警级别 |
|------|--------|----------|----------|
| 匹配池深度（等待玩家数） | Prometheus `battle_matching_pool_size` | <10（匹配冷清）或 >5000（队列积压） | P2 |
| 匹配平均等待时间 | Prometheus `battle_matching_wait_time_avg` | >60s | P1 |
| 战场延迟 P99 | Prometheus `battle_field_latency_p99` | >200ms | P1 |
| 战场延迟 P50 | Prometheus `battle_field_latency_p50` | >50ms | P2 |
| 积分异常波动 | InfluxDB 离线分析 | 单玩家单场+500分 | P1 |
| 击杀/死亡比异常 | InfluxDB 离线分析 | K/D > 15 或 < 0.01 | P2 |
| 活跃战场数 | Prometheus `battle_active_field_count` | >50（容量压力） | P2 |
| Netty 连接数 | Prometheus `netty_active_connections` | >10000 | P2 |
| Redis 命令延迟 P99 | Redis SLOWLOG | >10ms | P2 |
| MySQL 慢查询 | MySQL slow_query_log | >500ms | P2 |
| JVM 堆内存使用率 | Prometheus `jvm_memory_used_bytes` | >85% 持续5分钟 | P1 |
| GC 停顿时间 | Prometheus `jvm_gc_pause_seconds_max` | >500ms | P1 |
| 奖励发放失败率 | Prometheus `battle_reward_failure_rate` | >1% | P1 |
| 反作弊告警数 | Prometheus `battle_anticheat_alert_count` | 单玩家>3次/小时 | P2 |

### 2.2 Grafana 监控面板

```
推荐面板布局:
┌─────────────────────────────────────────────────┐
│  赛季总览：参与人数 / 活跃战场 / 匹配池深度     │
├────────────────────┬────────────────────────────┤
│  战场延迟 P50/P99  │  匹配等待时间分布          │
├────────────────────┼────────────────────────────┤
│  JVM 堆/GC         │  Redis/MySQL 延迟         │
├────────────────────┼────────────────────────────┤
│  积分 TOP10 实时   │  反作弊告警趋势            │
└────────────────────┴────────────────────────────┘
```

PromQL 示例：

```promql
# 匹配池深度
battle_matching_pool_size{namespace="battle-platform-prod"}

# 战场延迟P99
histogram_quantile(0.99, rate(battle_field_latency_seconds_bucket[5m]))

# JVM堆使用率
jvm_memory_used_bytes{area="heap"} / jvm_memory_max_bytes{area="heap"} * 100

# GC最大停顿
max_over_time(jvm_gc_pause_seconds_max[5m])

# 奖励发放失败率
rate(battle_reward_failure_total[5m]) / rate(battle_reward_total[5m]) * 100
```

### 2.3 积分异常告警规则

```yaml
# 积分异常检测（InfluxDB Flux查询，每10分钟执行）
# 规则1：单场积分超过500分
from(bucket: "battle-events")
  |> range(start: -10m)
  |> filter(fn: (r) => r._measurement == "score_change" and r._value > 500)
  |> aggregateWindow(every: 10m, fn: count, createEmpty: false)
  |> yield(name: "high_score_alert")

# 规则2：同一玩家连续5次击杀间隔<1秒（疑似外挂）
from(bucket: "battle-events")
  |> range(start: -5m)
  |> filter(fn: (r) => r._measurement == "kill_event")
  |> group(columns: ["killer_id", "battle_id"])
  |> sort(columns: ["_time"])
  |> difference(columns: ["_time"])
  |> filter(fn: (r) => r._value < 1000000000)  # <1秒（纳秒）
  |> aggregateWindow(every: 5m, fn: count, createEmpty: false)
  |> filter(fn: (r) => r._value >= 5)
  |> yield(name: "rapid_kill_alert")
```

### 2.4 紧急干预操作

```bash
# 紧急暂停匹配（不停止已进行中的战场）
curl -X POST https://battle.example.com/api/admin/matching/pause \
  -H "Authorization: Bearer $ADMIN_TOKEN"

# 紧急踢出可疑玩家
curl -X POST https://battle.example.com/api/admin/players/{playerId}/kick \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -d '{"reason": "suspected_cheating"}'

# 紧急封禁玩家
curl -X POST https://battle.example.com/api/admin/players/{playerId}/ban \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -d '{"reason": "confirmed_cheating", "duration": "PERMANENT"}'

# 标记异常对局（后续审核后可撤销积分）
curl -X POST https://battle.example.com/api/admin/battles/{battleId}/flag \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -d '{"reason": "massive_score_anomaly", "anomalous": true}'

# 手动触发排行榜快照
curl -X POST https://battle.example.com/api/admin/leaderboard/snapshot \
  -H "Authorization: Bearer $ADMIN_TOKEN"
```

---

## 三、赛季结束归档

### 3.1 归档时间线

```
赛季结束时间 T0
  │
  ├─ T0          系统自动将赛季状态改为 FINISHED，停止匹配
  │
  ├─ T0+5min     等待进行中的战场自然结束（最长5分钟）
  │
  ├─ T0+10min    执行排行榜归档到MySQL
  │
  ├─ T0+15min    执行奖励计算和发放
  │
  ├─ T0+30min    验证奖励发放完成率
  │
  ├─ T0+1h       执行数据归档脚本
  │
  └─ T0+2h       归档完成，确认数据完整性
```

### 3.2 归档操作步骤

```bash
# Step 1: 确认赛季已结束
curl https://battle.example.com/api/admin/seasons/{seasonId} \
  -H "Authorization: Bearer $ADMIN_TOKEN"
# 确认 status = FINISHED

# Step 2: 手动触发排行榜归档（如果自动归档未执行）
curl -X POST https://battle.example.com/api/admin/leaderboard/archive?seasonId={seasonId} \
  -H "Authorization: Bearer $ADMIN_TOKEN"

# Step 3: 验证MySQL归档数据
kubectl exec -it mysql-prod-0 -n battle-platform-prod -- \
  mysql -ubattle_platform -p battle_platform -e "
    SELECT ranking_type, COUNT(*), MAX(score), MIN(score)
    FROM season_ranking
    WHERE season_id = {seasonId}
    GROUP BY ranking_type;
  "
# 期望输出三行：TOTAL_SCORE / KILLS / GUILD_SCORE

# Step 4: 触发奖励计算和发放
curl -X POST https://battle.example.com/api/admin/reward/calculate?seasonId={seasonId} \
  -H "Authorization: Bearer $ADMIN_TOKEN"

# Step 5: 验证奖励发放状态
curl https://battle.example.com/api/admin/reward/status?seasonId={seasonId} \
  -H "Authorization: Bearer $ADMIN_TOKEN"
# 期望: {"total": N, "delivered": N, "failed": 0, "pending": 0}

# Step 6: 重试失败的奖励发放（如有）
curl -X POST https://battle.example.com/api/admin/reward/retry-failed?seasonId={seasonId} \
  -H "Authorization: Bearer $ADMIN_TOKEN"
```

### 3.3 数据库归档脚本

```sql
-- 归档脚本：赛季结束后执行
-- 将Redis排行榜数据持久化到MySQL（由LeaderboardService.archiveSeasonToMySQL自动执行）

-- 验证归档完整性
SELECT 'ranking_check' AS check_type,
       ranking_type,
       COUNT(*) AS record_count,
       SUM(CASE WHEN player_id IS NULL AND guild_id IS NULL THEN 1 ELSE 0 END) AS missing_ids
FROM season_ranking
WHERE season_id = {seasonId}
GROUP BY ranking_type;

-- 验证奖励完整性
SELECT 'reward_check' AS check_type,
       COUNT(*) AS total_records,
       SUM(CASE WHEN status = 'DELIVERED' THEN 1 ELSE 0 END) AS delivered,
       SUM(CASE WHEN status = 'FAILED' THEN 1 ELSE 0 END) AS failed,
       SUM(CASE WHEN status = 'PENDING' THEN 1 ELSE 0 END) AS pending
FROM reward_record
WHERE season_id = {seasonId};

-- 导出赛季统计数据
SELECT 'battle_stats' AS stat_type,
       COUNT(DISTINCT player_id) AS unique_players,
       SUM(kills) AS total_kills,
       SUM(deaths) AS total_deaths,
       SUM(captures) AS total_captures,
       AVG(total_score) AS avg_score,
       MAX(total_score) AS max_score
FROM player_battle_stat
WHERE season_id = {seasonId};
```

### 3.4 Redis 数据清理

```bash
# 归档完成后清理Redis赛季数据
kubectl exec -it redis-prod-0 -n battle-platform-prod -- redis-cli <<EOF
  DEL leaderboard:score
  DEL leaderboard:kills
  DEL leaderboard:guild
  DEL leaderboard:snapshot:score
  DEL leaderboard:snapshot:kills
  DEL leaderboard:snapshot:guild
  KEYS "matching:*" | xargs DEL
EOF

# 验证清理结果
kubectl exec -it redis-prod-0 -n battle-platform-prod -- redis-cli DBSIZE
```

### 3.5 回放文件归档

```bash
# 将回放文件归档到对象存储（S3/MinIO）
kubectl exec -it deployment/battle-platform -n battle-platform-prod -- \
  aws s3 sync /data/replays/ s3://battle-platform-replays/season-{seasonId}/ \
    --storage-class STANDARD_IA

# 验证归档
aws s3 ls s3://battle-platform-replays/season-{seasonId}/ --recursive | wc -l

# 清理本地回放文件（归档确认后）
kubectl exec -it deployment/battle-platform -n battle-platform-prod -- \
  rm -rf /data/replays/*
```

### 3.6 赛季归档完成检查清单

- [ ] 赛季状态为 `FINISHED`
- [ ] 所有战场实例已结束
- [ ] 排行榜数据已归档到 MySQL `season_ranking` 表
- [ ] 排行榜归档记录数与 Redis 一致
- [ ] 所有玩家奖励已发放（`DELIVERED` 状态）
- [ ] 失败奖励已重试并处理完毕
- [ ] 反作弊告警已全部审核处理
- [ ] 异常对局已标记并扣回违规积分
- [ ] Redis 赛季数据已清理
- [ ] 回放文件已归档到对象存储
- [ ] 赛季统计报告已生成并存档
- [ ] InfluxDB 时序数据保留策略确认（建议保留90天）
