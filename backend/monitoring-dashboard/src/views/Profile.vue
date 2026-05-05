<template>
  <div class="profile-page">
    <div class="page-header">
      <h1>玩家画像</h1>
      <div class="header-actions">
        <el-select v-model="selectedGameId" placeholder="选择游戏" style="width: 200px; margin-right: 16px">
          <el-option label="默认游戏" value="game_mmorpg_01" />
          <el-option label="测试游戏" value="test_game_01" />
        </el-select>
        <el-button type="primary" @click="generateAllProfiles">
          <el-icon><Refresh /></el-icon>
          批量生成画像
        </el-button>
      </div>
    </div>
    
    <el-row :gutter="20" class="summary-cards">
      <el-col :span="6">
        <el-card class="summary-card total">
          <div class="card-content">
            <div class="icon-wrapper">
              <el-icon :size="28"><User /></el-icon>
            </div>
            <div class="info">
              <div class="value">{{ profileStats.total_profiles }}</div>
              <div class="label">总画像数</div>
            </div>
          </div>
        </el-card>
      </el-col>
      <el-col :span="6">
        <el-card class="summary-card high-risk">
          <div class="card-content">
            <div class="icon-wrapper">
              <el-icon :size="28"><Warning /></el-icon>
            </div>
            <div class="info">
              <div class="value">{{ profileStats.by_churn_risk?.high || 0 }}</div>
              <div class="label">高流失风险</div>
            </div>
          </div>
        </el-card>
      </el-col>
      <el-col :span="6">
        <el-card class="summary-card paying">
          <div class="card-content">
            <div class="icon-wrapper">
              <el-icon :size="28"><Money /></el-icon>
            </div>
            <div class="info">
              <div class="value">{{ profileStats.by_payment_level?.['高付费'] || 0 }}</div>
              <div class="label">高付费玩家</div>
            </div>
          </div>
        </el-card>
      </el-col>
      <el-col :span="6">
        <el-card class="summary-card active">
          <div class="card-content">
            <div class="icon-wrapper">
              <el-icon :size="28"><TrendCharts /></el-icon>
            </div>
            <div class="info">
              <div class="value">{{ profileStats.by_activity_level?.['高活跃'] || 0 }}</div>
              <div class="label">高活跃玩家</div>
            </div>
          </div>
        </el-card>
      </el-col>
    </el-row>
    
    <el-row :gutter="20">
      <el-col :span="12">
        <el-card class="chart-card">
          <template #header>
            <span>流失风险分布</span>
          </template>
          <div ref="churnChartRef" class="chart-container"></div>
        </el-card>
      </el-col>
      <el-col :span="12">
        <el-card class="chart-card">
          <template #header>
            <span>活跃度分布</span>
          </template>
          <div ref="activityChartRef" class="chart-container"></div>
        </el-card>
      </el-col>
    </el-row>
    
    <el-row :gutter="20" style="margin-top: 20px">
      <el-col :span="12">
        <el-card class="list-card">
          <template #header>
            <div class="card-header">
              <span>高流失风险玩家</span>
              <el-tag type="danger" effect="light">需要关注</el-tag>
            </div>
          </template>
          <el-table :data="highRiskPlayers" style="width: 100%" max-height="400">
            <el-table-column prop="player_id" label="玩家ID" min-width="150" />
            <el-table-column label="流失风险" width="100" align="center">
              <template #default>
                <el-tag type="danger" size="small">高风险</el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="activity_score" label="活跃度" width="100" align="right">
              <template #default="{ row }">
                <span class="score-text">{{ row.activity_score?.toFixed(1) || 0 }}</span>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="100" align="center">
              <template #default="{ row }">
                <el-button type="primary" link @click="viewPlayerProfile(row.player_id)">
                  查看
                </el-button>
              </template>
            </el-table-column>
          </el-table>
        </el-card>
      </el-col>
      <el-col :span="12">
        <el-card class="list-card">
          <template #header>
            <div class="card-header">
              <span>高付费玩家 TOP 10</span>
              <el-tag type="success" effect="light">核心用户</el-tag>
            </div>
          </template>
          <el-table :data="topPayers" style="width: 100%" max-height="400">
            <el-table-column type="index" label="排名" width="60" align="center" />
            <el-table-column prop="player_id" label="玩家ID" min-width="150" />
            <el-table-column prop="pay_amount" label="累计付费" width="120" align="right">
              <template #default="{ row }">
                <span class="amount-text">¥{{ row.pay_amount?.toFixed(2) || 0 }}</span>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="100" align="center">
              <template #default="{ row }">
                <el-button type="primary" link @click="viewPlayerProfile(row.player_id)">
                  查看
                </el-button>
              </template>
            </el-table-column>
          </el-table>
        </el-card>
      </el-col>
    </el-row>
    
    <el-card class="search-card" style="margin-top: 20px">
      <template #header>
        <span>玩家查询</span>
      </template>
      <el-form :inline="true">
        <el-form-item label="玩家ID">
          <el-input
            v-model="searchPlayerId"
            placeholder="请输入玩家ID"
            style="width: 250px"
            clearable
          />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" @click="searchPlayer">
            <el-icon><Search /></el-icon>
            查询
          </el-button>
        </el-form-item>
      </el-form>
      
      <div v-if="selectedPlayer" class="player-detail">
        <el-divider>玩家详情</el-divider>
        <el-descriptions :column="4" border>
          <el-descriptions-item label="玩家ID">{{ selectedPlayer.player_id }}</el-descriptions-item>
          <el-descriptions-item label="等级">{{ selectedPlayer.level }}</el-descriptions-item>
          <el-descriptions-item label="VIP等级">{{ selectedPlayer.vip_level }}</el-descriptions-item>
          <el-descriptions-item label="流失风险">
            <el-tag :type="selectedPlayer.churn_risk === 'low' ? 'success' : selectedPlayer.churn_risk === 'medium' ? 'warning' : 'danger'">
              {{ selectedPlayer.churn_risk === 'low' ? '低' : selectedPlayer.churn_risk === 'medium' ? '中' : '高' }}
            </el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="累计游戏时长">{{ selectedPlayer.total_play_time }} 分钟</el-descriptions-item>
          <el-descriptions-item label="累计付费">¥{{ selectedPlayer.pay_amount?.toFixed(2) }}</el-descriptions-item>
          <el-descriptions-item label="最后活跃">
            {{ selectedPlayer.last_active ? new Date(selectedPlayer.last_active).toLocaleString() : '-' }}
          </el-descriptions-item>
          <el-descriptions-item label="活跃度评分">{{ selectedPlayer.activity_score?.toFixed(1) }}</el-descriptions-item>
        </el-descriptions>
        
        <div class="player-tags" style="margin-top: 20px">
          <span class="tags-label">画像标签：</span>
          <el-tag
            v-for="tag in selectedPlayer.profile_tags"
            :key="tag"
            type="primary"
            effect="light"
            style="margin-right: 8px; margin-bottom: 8px"
          >
            {{ tag }}
          </el-tag>
        </div>
        
        <el-row :gutter="20" style="margin-top: 20px">
          <el-col :span="8">
            <el-card shadow="hover">
              <template #header>
                <span class="score-title">活跃度评分</span>
              </template>
              <el-progress
                :percentage="selectedPlayer.activity_score || 0"
                :color="getScoreColor(selectedPlayer.activity_score)"
                :stroke-width="20"
              />
            </el-card>
          </el-col>
          <el-col :span="8">
            <el-card shadow="hover">
              <template #header>
                <span class="score-title">付费评分</span>
              </template>
              <el-progress
                :percentage="selectedPlayer.payment_score || 0"
                :color="getScoreColor(selectedPlayer.payment_score)"
                :stroke-width="20"
              />
            </el-card>
          </el-col>
          <el-col :span="8">
            <el-card shadow="hover">
              <template #header>
                <span class="score-title">社交评分</span>
              </template>
              <el-progress
                :percentage="selectedPlayer.social_score || 0"
                :color="getScoreColor(selectedPlayer.social_score)"
                :stroke-width="20"
              />
            </el-card>
          </el-col>
        </el-row>
      </div>
    </el-card>
  </div>
</template>

<script setup>
import { ref, onMounted, onUnmounted } from 'vue'
import * as echarts from 'echarts'
import { profileApi } from '@/api'
import { ElMessage } from 'element-plus'

const selectedGameId = ref('game_mmorpg_01')
const searchPlayerId = ref('')
const selectedPlayer = ref(null)

const profileStats = ref({
  total_profiles: 0,
  by_churn_risk: { low: 0, medium: 0, high: 0 },
  by_activity_level: { '高活跃': 0, '中活跃': 0, '低活跃': 0 },
  by_payment_level: { '高付费': 0, '中付费': 0, '低付费': 0, '非付费': 0 }
})

const highRiskPlayers = ref([
  { player_id: 'player_001', churn_risk: 'high', activity_score: 15.2 },
  { player_id: 'player_002', churn_risk: 'high', activity_score: 12.8 },
  { player_id: 'player_003', churn_risk: 'high', activity_score: 8.5 },
  { player_id: 'player_004', churn_risk: 'high', activity_score: 5.2 }
])

const topPayers = ref([
  { player_id: 'player_101', pay_amount: 15800.50 },
  { player_id: 'player_102', pay_amount: 12300.00 },
  { player_id: 'player_103', pay_amount: 9800.50 },
  { player_id: 'player_104', pay_amount: 7500.00 },
  { player_id: 'player_105', pay_amount: 6200.80 }
])

const churnChartRef = ref(null)
const activityChartRef = ref(null)

let churnChart = null
let activityChart = null

const getScoreColor = (score) => {
  if (score >= 70) return '#10b981'
  if (score >= 40) return '#f59e0b'
  return '#ef4444'
}

const initChurnChart = () => {
  const chart = echarts.init(churnChartRef.value)
  
  chart.setOption({
    tooltip: {
      trigger: 'item',
      formatter: '{b}: {c}人 ({d}%)'
    },
    legend: {
      bottom: 10,
      data: ['低风险', '中风险', '高风险']
    },
    series: [
      {
        type: 'pie',
        radius: ['40%', '70%'],
        avoidLabelOverlap: false,
        itemStyle: {
          borderRadius: 10,
          borderColor: '#fff',
          borderWidth: 2
        },
        label: {
          show: true,
          formatter: '{b}: {c}'
        },
        emphasis: {
          label: {
            show: true,
            fontSize: 16,
            fontWeight: 'bold'
          }
        },
        data: [
          { value: 8500, name: '低风险', itemStyle: { color: '#10b981' } },
          { value: 2800, name: '中风险', itemStyle: { color: '#f59e0b' } },
          { value: 1200, name: '高风险', itemStyle: { color: '#ef4444' } }
        ]
      }
    ]
  })
  
  churnChart = chart
}

const initActivityChart = () => {
  const chart = echarts.init(activityChartRef.value)
  
  chart.setOption({
    tooltip: {
      trigger: 'axis',
      axisPointer: {
        type: 'shadow'
      }
    },
    grid: {
      left: '3%',
      right: '4%',
      bottom: '3%',
      containLabel: true
    },
    xAxis: {
      type: 'category',
      data: ['高活跃', '中活跃', '低活跃', '非活跃']
    },
    yAxis: {
      type: 'value'
    },
    series: [
      {
        type: 'bar',
        barWidth: '60%',
        itemStyle: {
          borderRadius: [4, 4, 0, 0]
        },
        data: [
          { value: 4500, itemStyle: { color: '#10b981' } },
          { value: 5200, itemStyle: { color: '#3b82f6' } },
          { value: 2300, itemStyle: { color: '#f59e0b' } },
          { value: 500, itemStyle: { color: '#9ca3af' } }
        ]
      }
    ]
  })
  
  activityChart = chart
}

const loadProfileStats = async () => {
  try {
    const data = await profileApi.getStatsSummary(selectedGameId.value)
    if (data) {
      profileStats.value = data
    }
  } catch (e) {
    console.error('Failed to load profile stats:', e)
  }
}

const loadHighRiskPlayers = async () => {
  try {
    const data = await profileApi.getHighRiskPlayers(selectedGameId.value, 50)
    if (data?.players) {
      highRiskPlayers.value = data.players
    }
  } catch (e) {
    console.error('Failed to load high risk players:', e)
  }
}

const loadTopPayers = async () => {
  try {
    const data = await profileApi.getTopPayers(selectedGameId.value, 10)
    if (data?.players) {
      topPayers.value = data.players
    }
  } catch (e) {
    console.error('Failed to load top payers:', e)
  }
}

const searchPlayer = async () => {
  if (!searchPlayerId.value.trim()) {
    ElMessage.warning('请输入玩家ID')
    return
  }
  
  try {
    const data = await profileApi.getPlayerProfile(searchPlayerId.value)
    selectedPlayer.value = data
    ElMessage.success('查询成功')
  } catch (e) {
    ElMessage.error('未找到该玩家')
    selectedPlayer.value = {
      player_id: searchPlayerId.value,
      level: 45,
      vip_level: 3,
      total_play_time: 720,
      pay_amount: 500.00,
      last_active: new Date().toISOString(),
      churn_risk: 'low',
      activity_score: 75.5,
      payment_score: 60.0,
      social_score: 45.0,
      profile_tags: ['高活跃', '付费玩家', '社交型']
    }
  }
}

const viewPlayerProfile = (playerId) => {
  searchPlayerId.value = playerId
  searchPlayer()
}

const generateAllProfiles = async () => {
  try {
    ElMessage.info('开始批量生成画像...')
    const data = await profileApi.generateProfiles({ game_id: selectedGameId.value })
    if (data?.success) {
      ElMessage.success(`成功生成 ${data.processed_count} 个玩家画像`)
      loadProfileStats()
    }
  } catch (e) {
    ElMessage.error('批量生成失败')
  }
}

onMounted(() => {
  initChurnChart()
  initActivityChart()
  loadProfileStats()
})

onUnmounted(() => {
  churnChart?.dispose()
  activityChart?.dispose()
})
</script>

<style lang="scss" scoped>
.profile-page {
  .page-header {
    display: flex;
    justify-content: space-between;
    align-items: center;
    margin-bottom: 20px;
    
    h1 {
      font-size: 24px;
      color: #1f2937;
      margin: 0;
    }
    
    .header-actions {
      display: flex;
      align-items: center;
    }
  }
  
  .summary-cards {
    margin-bottom: 20px;
    
    .summary-card {
      border: none;
      border-radius: 12px;
      
      .card-content {
        display: flex;
        align-items: center;
        
        .icon-wrapper {
          width: 56px;
          height: 56px;
          border-radius: 12px;
          display: flex;
          align-items: center;
          justify-content: center;
          margin-right: 16px;
        }
        
        .info {
          .value {
            font-size: 28px;
            font-weight: 700;
            color: #1f2937;
            line-height: 1.2;
          }
          
          .label {
            font-size: 14px;
            color: #6b7280;
            margin-top: 4px;
          }
        }
      }
    }
    
    .total {
      .icon-wrapper {
        background: linear-gradient(135deg, #3b82f6 0%, #2563eb 100%);
        color: #fff;
      }
    }
    
    .high-risk {
      .icon-wrapper {
        background: linear-gradient(135deg, #ef4444 0%, #dc2626 100%);
        color: #fff;
      }
    }
    
    .paying {
      .icon-wrapper {
        background: linear-gradient(135deg, #10b981 0%, #059669 100%);
        color: #fff;
      }
    }
    
    .active {
      .icon-wrapper {
        background: linear-gradient(135deg, #8b5cf6 0%, #7c3aed 100%);
        color: #fff;
      }
    }
  }
  
  .chart-card {
    border: none;
    border-radius: 12px;
    
    :deep(.el-card__header) {
      border-bottom: 1px solid #f3f4f6;
      
      span {
        font-weight: 600;
        color: #1f2937;
        font-size: 15px;
      }
    }
  }
  
  .chart-container {
    height: 300px;
    width: 100%;
  }
  
  .list-card {
    border: none;
    border-radius: 12px;
    
    :deep(.el-card__header) {
      border-bottom: 1px solid #f3f4f6;
      
      .card-header {
        display: flex;
        align-items: center;
        gap: 16px;
        
        span {
          font-weight: 600;
          color: #1f2937;
          font-size: 15px;
        }
      }
    }
    
    :deep(.el-table) {
      .score-text {
        color: #6b7280;
        font-family: 'SF Mono', Monaco, monospace;
      }
      
      .amount-text {
        color: #10b981;
        font-weight: 600;
        font-family: 'SF Mono', Monaco, monospace;
      }
    }
  }
  
  .search-card {
    border: none;
    border-radius: 12px;
    
    :deep(.el-card__header) {
      border-bottom: 1px solid #f3f4f6;
      
      span {
        font-weight: 600;
        color: #1f2937;
        font-size: 15px;
      }
    }
    
    .player-detail {
      .player-tags {
        .tags-label {
          color: #6b7280;
          margin-right: 8px;
        }
      }
      
      .score-title {
        font-weight: 600;
        color: #1f2937;
      }
    }
  }
}
</style>
