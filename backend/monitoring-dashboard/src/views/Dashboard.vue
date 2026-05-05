<template>
  <div class="dashboard-page">
    <div class="page-header">
      <h1>实时监控</h1>
      <el-select v-model="selectedGameId" placeholder="选择游戏" style="width: 200px">
        <el-option label="默认游戏" value="game_mmorpg_01" />
        <el-option label="测试游戏" value="test_game_01" />
      </el-select>
    </div>
    
    <el-row :gutter="20" class="stats-cards">
      <el-col :span="6">
        <el-card class="stat-card online-card">
          <div class="stat-content">
            <div class="stat-icon">
              <el-icon :size="32"><User /></el-icon>
            </div>
            <div class="stat-info">
              <div class="stat-value">{{ onlineStats.online_count || 0 }}</div>
              <div class="stat-label">当前在线人数</div>
            </div>
          </div>
          <div class="stat-trend" v-if="onlineStats.trend">
            <span class="trend-up">↑ 5.2%</span>
            <span class="trend-label">较1小时前</span>
          </div>
        </el-card>
      </el-col>
      <el-col :span="6">
        <el-card class="stat-card peak-card">
          <div class="stat-content">
            <div class="stat-icon">
              <el-icon :size="32"><TrendCharts /></el-icon>
            </div>
            <div class="stat-info">
              <div class="stat-value">{{ onlineStats.peak_today || 0 }}</div>
              <div class="stat-label">今日峰值</div>
            </div>
          </div>
          <div class="stat-time">
            <span>{{ formatTime(onlineStats.sample_time) }}</span>
          </div>
        </el-card>
      </el-col>
      <el-col :span="6">
        <el-card class="stat-card revenue-card">
          <div class="stat-content">
            <div class="stat-icon">
              <el-icon :size="32"><Money /></el-icon>
            </div>
            <div class="stat-info">
              <div class="stat-value">¥{{ dailyMetrics.revenue || 0 }}</div>
              <div class="stat-label">今日收入</div>
            </div>
          </div>
          <div class="stat-trend">
            <span class="trend-up">↑ 12.5%</span>
            <span class="trend-label">较昨日</span>
          </div>
        </el-card>
      </el-col>
      <el-col :span="6">
        <el-card class="stat-card dau-card">
          <div class="stat-content">
            <div class="stat-icon">
              <el-icon :size="32"><DataLine /></el-icon>
            </div>
            <div class="stat-info">
              <div class="stat-value">{{ dailyMetrics.dau || 0 }}</div>
              <div class="stat-label">今日活跃(DAU)</div>
            </div>
          </div>
          <div class="stat-sub">
            <span>新增: {{ dailyMetrics.dnu || 0 }}</span>
            <span>付费: {{ dailyMetrics.dpu || 0 }}</span>
          </div>
        </el-card>
      </el-col>
    </el-row>
    
    <el-row :gutter="20">
      <el-col :span="16">
        <el-card class="chart-card">
          <template #header>
            <div class="card-header">
              <span>在线人数趋势</span>
              <el-radio-group v-model="timeRange" size="small">
                <el-radio-button label="1h">1小时</el-radio-button>
                <el-radio-button label="6h">6小时</el-radio-button>
                <el-radio-button label="24h">24小时</el-radio-button>
              </el-radio-group>
            </div>
          </template>
          <div ref="trendChartRef" class="chart-container"></div>
        </el-card>
      </el-col>
      <el-col :span="8">
        <el-card class="chart-card">
          <template #header>
            <span>服务器分布</span>
          </template>
          <div ref="serverChartRef" class="chart-container"></div>
        </el-card>
      </el-col>
    </el-row>
    
    <el-row :gutter="20" style="margin-top: 20px">
      <el-col :span="12">
        <el-card class="chart-card">
          <template #header>
            <span>事件类型分布</span>
          </template>
          <div ref="eventChartRef" class="chart-container"></div>
        </el-card>
      </el-col>
      <el-col :span="12">
        <el-card class="chart-card">
          <template #header>
            <span>留存率趋势</span>
          </template>
          <div ref="retentionChartRef" class="chart-container"></div>
        </el-card>
      </el-col>
    </el-row>
  </div>
</template>

<script setup>
import { ref, onMounted, onUnmounted } from 'vue'
import * as echarts from 'echarts'
import dayjs from 'dayjs'
import { statsApi } from '@/api'

const selectedGameId = ref('game_mmorpg_01')
const timeRange = ref('1h')
const onlineStats = ref({})
const dailyMetrics = ref({
  revenue: 15800,
  dau: 12580,
  dnu: 856,
  dpu: 620
})

const trendChartRef = ref(null)
const serverChartRef = ref(null)
const eventChartRef = ref(null)
const retentionChartRef = ref(null)

let trendChart = null
let serverChart = null
let eventChart = null
let retentionChart = null

const formatTime = (time) => {
  if (!time) return '-'
  return dayjs(time).format('HH:mm:ss')
}

const initTrendChart = () => {
  const chart = echarts.init(trendChartRef.value)
  const now = dayjs()
  const times = []
  const values = []
  
  for (let i = 59; i >= 0; i--) {
    times.push(now.subtract(i, 'minute').format('HH:mm'))
    values.push(Math.floor(10000 + Math.random() * 5000))
  }
  
  chart.setOption({
    tooltip: {
      trigger: 'axis'
    },
    grid: {
      left: '3%',
      right: '4%',
      bottom: '3%',
      containLabel: true
    },
    xAxis: {
      type: 'category',
      boundaryGap: false,
      data: times
    },
    yAxis: {
      type: 'value'
    },
    series: [
      {
        name: '在线人数',
        type: 'line',
        smooth: true,
        sampling: 'lttb',
        itemStyle: {
          color: '#3b82f6'
        },
        areaStyle: {
          color: new echarts.graphic.LinearGradient(0, 0, 0, 1, [
            { offset: 0, color: 'rgba(59, 130, 246, 0.5)' },
            { offset: 1, color: 'rgba(59, 130, 246, 0.05)' }
          ])
        },
        data: values
      }
    ]
  })
  
  trendChart = chart
}

const initServerChart = () => {
  const chart = echarts.init(serverChartRef.value)
  
  chart.setOption({
    tooltip: {
      trigger: 'item',
      formatter: '{a} <br/>{b}: {c} ({d}%)'
    },
    legend: {
      orient: 'vertical',
      right: 10,
      top: 'center'
    },
    series: [
      {
        name: '服务器分布',
        type: 'pie',
        radius: ['40%', '70%'],
        avoidLabelOverlap: false,
        itemStyle: {
          borderRadius: 10,
          borderColor: '#fff',
          borderWidth: 2
        },
        label: {
          show: false
        },
        emphasis: {
          label: {
            show: true,
            fontSize: 16,
            fontWeight: 'bold'
          }
        },
        labelLine: {
          show: false
        },
        data: [
          { value: 5280, name: 'server_cn_01' },
          { value: 4300, name: 'server_cn_02' },
          { value: 3000, name: 'server_cn_03' },
          { value: 1500, name: 'server_cn_04' }
        ]
      }
    ]
  })
  
  serverChart = chart
}

const initEventChart = () => {
  const chart = echarts.init(eventChartRef.value)
  
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
      data: ['登录', '登出', '支付', '升级', '任务', '社交'],
      axisLabel: {
        rotate: 30
      }
    },
    yAxis: {
      type: 'value'
    },
    series: [
      {
        type: 'bar',
        barWidth: '60%',
        itemStyle: {
          color: new echarts.graphic.LinearGradient(0, 0, 0, 1, [
            { offset: 0, color: '#10b981' },
            { offset: 1, color: '#059669' }
          ]),
          borderRadius: [4, 4, 0, 0]
        },
        data: [12500, 11800, 2560, 3200, 8900, 6700]
      }
    ]
  })
  
  eventChart = chart
}

const initRetentionChart = () => {
  const chart = echarts.init(retentionChartRef.value)
  
  const dates = []
  const now = dayjs()
  for (let i = 6; i >= 0; i--) {
    dates.push(now.subtract(i, 'day').format('MM-DD'))
  }
  
  chart.setOption({
    tooltip: {
      trigger: 'axis'
    },
    legend: {
      data: ['次日留存', '7日留存', '30日留存']
    },
    grid: {
      left: '3%',
      right: '4%',
      bottom: '3%',
      containLabel: true
    },
    xAxis: {
      type: 'category',
      data: dates
    },
    yAxis: {
      type: 'value',
      max: 100,
      axisLabel: {
        formatter: '{value}%'
      }
    },
    series: [
      {
        name: '次日留存',
        type: 'line',
        smooth: true,
        data: [42.5, 45.2, 43.8, 41.5, 46.3, 44.8, 42.0]
      },
      {
        name: '7日留存',
        type: 'line',
        smooth: true,
        data: [18.7, 20.3, 19.8, 17.5, 21.2, 19.5, 18.0]
      },
      {
        name: '30日留存',
        type: 'line',
        smooth: true,
        data: [6.8, 7.2, 7.0, 6.5, 7.5, 6.9, 6.3]
      }
    ]
  })
  
  retentionChart = chart
}

const fetchOnlineStats = async () => {
  try {
    const data = await statsApi.getOnlineStats(selectedGameId.value)
    onlineStats.value = data || {
      online_count: 12580,
      peak_today: 15800,
      sample_time: new Date().toISOString(),
      server_distribution: {
        'server_cn_01': 5280,
        'server_cn_02': 4300,
        'server_cn_03': 3000
      }
    }
  } catch (e) {
    onlineStats.value = {
      online_count: 12580,
      peak_today: 15800,
      sample_time: new Date().toISOString()
    }
  }
}

let updateInterval = null

onMounted(() => {
  initTrendChart()
  initServerChart()
  initEventChart()
  initRetentionChart()
  fetchOnlineStats()
  
  updateInterval = setInterval(fetchOnlineStats, 60000)
  
  window.addEventListener('resize', () => {
    trendChart?.resize()
    serverChart?.resize()
    eventChart?.resize()
    retentionChart?.resize()
  })
})

onUnmounted(() => {
  if (updateInterval) {
    clearInterval(updateInterval)
  }
  trendChart?.dispose()
  serverChart?.dispose()
  eventChart?.dispose()
  retentionChart?.dispose()
})
</script>

<style lang="scss" scoped>
.dashboard-page {
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
  }
  
  .stats-cards {
    margin-bottom: 20px;
    
    .stat-card {
      border: none;
      border-radius: 12px;
      
      .stat-content {
        display: flex;
        align-items: center;
        margin-bottom: 12px;
        
        .stat-icon {
          width: 56px;
          height: 56px;
          border-radius: 12px;
          display: flex;
          align-items: center;
          justify-content: center;
          margin-right: 16px;
        }
        
        .stat-info {
          .stat-value {
            font-size: 28px;
            font-weight: 600;
            color: #1f2937;
            line-height: 1.2;
          }
          
          .stat-label {
            font-size: 14px;
            color: #6b7280;
            margin-top: 4px;
          }
        }
      }
      
      .stat-trend {
        display: flex;
        align-items: center;
        font-size: 13px;
        
        .trend-up {
          color: #10b981;
          font-weight: 600;
          margin-right: 8px;
        }
        
        .trend-label {
          color: #6b7280;
        }
      }
      
      .stat-time {
        font-size: 13px;
        color: #6b7280;
      }
      
      .stat-sub {
        display: flex;
        justify-content: space-between;
        font-size: 13px;
        color: #6b7280;
      }
    }
    
    .online-card {
      .stat-icon {
        background: linear-gradient(135deg, #3b82f6 0%, #2563eb 100%);
        color: #fff;
      }
    }
    
    .peak-card {
      .stat-icon {
        background: linear-gradient(135deg, #f59e0b 0%, #d97706 100%);
        color: #fff;
      }
    }
    
    .revenue-card {
      .stat-icon {
        background: linear-gradient(135deg, #10b981 0%, #059669 100%);
        color: #fff;
      }
    }
    
    .dau-card {
      .stat-icon {
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
      padding: 16px 20px;
      
      .card-header {
        display: flex;
        justify-content: space-between;
        align-items: center;
      }
      
      span {
        font-weight: 600;
        color: #1f2937;
        font-size: 15px;
      }
    }
  }
  
  .chart-container {
    height: 350px;
    width: 100%;
  }
}
</style>
