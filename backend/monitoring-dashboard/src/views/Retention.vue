<template>
  <div class="retention-page">
    <div class="page-header">
      <h1>留存分析</h1>
      <div class="header-actions">
        <el-date-picker
          v-model="dateRange"
          type="daterange"
          range-separator="至"
          start-placeholder="开始日期"
          end-placeholder="结束日期"
          style="width: 300px; margin-right: 16px"
        />
        <el-button type="primary" @click="loadRetentionData">
          <el-icon><Search /></el-icon>
          查询
        </el-button>
      </div>
    </div>
    
    <el-row :gutter="20" class="retention-cards">
      <el-col :span="4">
        <el-card class="retention-stat-card day1">
          <div class="stat-value">{{ retentionData.day1_retention }}%</div>
          <div class="stat-label">次日留存</div>
        </el-card>
      </el-col>
      <el-col :span="4">
        <el-card class="retention-stat-card day3">
          <div class="stat-value">{{ retentionData.day3_retention }}%</div>
          <div class="stat-label">3日留存</div>
        </el-card>
      </el-col>
      <el-col :span="4">
        <el-card class="retention-stat-card day7">
          <div class="stat-value">{{ retentionData.day7_retention }}%</div>
          <div class="stat-label">7日留存</div>
        </el-card>
      </el-col>
      <el-col :span="4">
        <el-card class="retention-stat-card day14">
          <div class="stat-value">{{ retentionData.day14_retention }}%</div>
          <div class="stat-label">14日留存</div>
        </el-card>
      </el-col>
      <el-col :span="4">
        <el-card class="retention-stat-card day30">
          <div class="stat-value">{{ retentionData.day30_retention }}%</div>
          <div class="stat-label">30日留存</div>
        </el-card>
      </el-col>
      <el-col :span="4">
        <el-card class="retention-stat-card avg">
          <div class="stat-value">8.2</div>
          <div class="stat-label">平均生命周期(天)</div>
        </el-card>
      </el-col>
    </el-row>
    
    <el-row :gutter="20">
      <el-col :span="16">
        <el-card class="chart-card">
          <template #header>
            <span>留存曲线趋势</span>
          </template>
          <div ref="trendChartRef" class="chart-container"></div>
        </el-card>
      </el-col>
      <el-col :span="8">
        <el-card class="chart-card">
          <template #header>
            <span>留存趋势对比</span>
          </template>
          <div ref="radarChartRef" class="chart-container"></div>
        </el-card>
      </el-col>
    </el-row>
    
    <el-card class="matrix-card" style="margin-top: 20px">
      <template #header>
        <div class="card-header">
          <span>留存矩阵</span>
          <el-tag type="info">按注册日分组的留存率变化</el-tag>
        </div>
      </template>
      <div class="matrix-container">
        <table class="retention-matrix">
          <thead>
            <tr>
              <th>注册日</th>
              <th>新增用户</th>
              <th v-for="day in retentionData.days" :key="day">
                D{{ day }}
              </th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="(cohort, index) in retentionData.cohorts" :key="cohort">
              <td class="cohort-date">{{ cohort }}</td>
              <td class="new-users">{{ 1000 + index * 100 }}</td>
              <td
                v-for="(rate, dayIndex) in retentionData.retention_matrix[index]"
                :key="dayIndex"
                :class="getMatrixClass(rate)"
              >
                <span class="rate-value">{{ rate }}%</span>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </el-card>
  </div>
</template>

<script setup>
import { ref, onMounted, onUnmounted } from 'vue'
import * as echarts from 'echarts'
import dayjs from 'dayjs'
import { analysisApi } from '@/api'

const dateRange = ref([
  dayjs().subtract(7, 'day').toDate(), dayjs().toDate()
])

const retentionData = ref({
  day1_retention: 42.5,
  day3_retention: 28.3,
  day7_retention: 18.7,
  day14_retention: 12.1,
  day30_retention: 6.8,
  retention_matrix: [
    [100, 42.5, 28.3, 18.7, 12.1, 6.8],
    [100, 45.2, 30.1, 20.3, 13.5, 7.2],
    [100, 43.8, 29.5, 19.8, 12.8, 7.0],
    [100, 41.5, 27.8, 18.2, 11.8, 6.5],
    [100, 46.3, 31.2, 21.2, 14.0, 7.5],
    [100, 44.8, 29.8, 19.5, 12.9, 6.9],
    [100, 42.0, 28.0, 18.0, 12.0, 6.3]
  ],
  cohorts: [],
  days: [0, 1, 3, 7, 14, 30]
})

for (let i = 6; i >= 0; i--) {
  retentionData.value.cohorts.push(dayjs().subtract(i, 'day').format('MM-DD'))
}

const trendChartRef = ref(null)
const radarChartRef = ref(null)

let trendChart = null
let radarChart = null

const getMatrixClass = (rate) => {
  if (rate >= 40) return 'rate-high'
  if (rate >= 20) return 'rate-medium'
  if (rate >= 10) return 'rate-low'
  return 'rate-very-low'
}

const initTrendChart = () => {
  const chart = echarts.init(trendChartRef.value)
  
  chart.setOption({
    tooltip: {
      trigger: 'axis',
      formatter: (params) => {
        let result = params[0].axisValue + '<br/>'
        params.forEach(param => {
          result += `${param.marker}${param.seriesName}: ${param.value}%<br/>`
        })
        return result
      }
    },
    legend: {
      data: ['本次统计'],
      bottom: 10
    },
    grid: {
      left: '3%',
      right: '4%',
      bottom: '15%',
      top: '10%',
      containLabel: true
    },
    xAxis: {
      type: 'category',
      name: '天数',
      data: ['D1', 'D3', 'D7', 'D14', 'D30']
    },
    yAxis: {
      type: 'value',
      name: '留存率(%)',
      max: 100,
      axisLabel: {
        formatter: '{value}%'
      }
    },
    series: [
      {
        name: '本次统计',
        type: 'line',
        smooth: true,
        symbol: 'circle',
        symbolSize: 8,
        lineStyle: {
          width: 3,
          color: '#3b82f6'
        },
        itemStyle: {
          color: '#3b82f6'
        },
        areaStyle: {
          color: new echarts.graphic.LinearGradient(0, 0, 0, 1, [
            { offset: 0, color: 'rgba(59, 130, 246, 0.3)' },
            { offset: 1, color: 'rgba(59, 130, 246, 0.05)' }
          ])
        },
        data: [42.5, 28.3, 18.7, 12.1, 6.8]
      }
    ]
  })
  
  trendChart = chart
}

const initRadarChart = () => {
  const chart = echarts.init(radarChartRef.value)
  
  chart.setOption({
    tooltip: {
      trigger: 'item'
    },
    legend: {
      data: ['本周', '上周'],
      bottom: 10
    },
    radar: {
      indicator: [
        { name: '次日留存', max: 100 },
        { name: '3日留存', max: 100 },
        { name: '7日留存', max: 100 },
        { name: '14日留存', max: 100 },
        { name: '30日留存', max: 100 }
      ],
      radius: '65%',
      center: ['50%', '45%']
    },
    series: [
      {
        type: 'radar',
        data: [
          {
            value: [42.5, 28.3, 18.7, 12.1, 6.8],
            name: '本周',
            areaStyle: {
              color: 'rgba(59, 130, 246, 0.3)'
            },
            lineStyle: {
              color: '#3b82f6',
              width: 2
            }
          },
          {
            value: [38.5, 25.3, 16.7, 10.1, 5.8],
            name: '上周',
            areaStyle: {
              color: 'rgba(251, 191, 36, 0.3)'
            },
            lineStyle: {
              color: '#fbbf24',
              width: 2
            }
          }
        ]
      }
    ]
  })
  
  radarChart = chart
}

const loadRetentionData = async () => {
  try {
    const data = await analysisApi.getRetention({
      start_date: dayjs(dateRange.value[0]).format('YYYY-MM-DD'),
      end_date: dayjs(dateRange.value[1]).format('YYYY-MM-DD')
    })
    retentionData.value = { ...retentionData.value, ...data }
  } catch (e) {
    console.error('Failed to load retention data:', e)
  }
}

onMounted(() => {
  initTrendChart()
  initRadarChart()
})

onUnmounted(() => {
  trendChart?.dispose()
  radarChart?.dispose()
})
</script>

<style lang="scss" scoped>
.retention-page {
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
  
  .retention-cards {
    margin-bottom: 20px;
    
    .retention-stat-card {
      border: none;
      border-radius: 12px;
      text-align: center;
      
      .stat-value {
        font-size: 32px;
        font-weight: 700;
        margin-bottom: 8px;
      }
      
      .stat-label {
        font-size: 14px;
        color: #6b7280;
      }
    }
    
    .day1 {
      .stat-value { color: #10b981; }
    }
    
    .day3 {
      .stat-value { color: #3b82f6; }
    }
    
    .day7 {
      .stat-value { color: #8b5cf6; }
    }
    
    .day14 {
      .stat-value { color: #f59e0b; }
    }
    
    .day30 {
      .stat-value { color: #ef4444; }
    }
    
    .avg {
      .stat-value { color: #06b6d4; }
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
    height: 320px;
    width: 100%;
  }
  
  .matrix-card {
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
    
    .matrix-container {
      overflow-x: auto;
    }
    
    .retention-matrix {
      width: 100%;
      border-collapse: collapse;
      
      th {
        background: #f8fafc;
        padding: 14px 16px;
        text-align: center;
        font-weight: 600;
        color: #374151;
        border: 1px solid #e5e7eb;
        font-size: 13px;
      }
      
      td {
        padding: 14px 16px;
        text-align: center;
        border: 1px solid #e5e7eb;
        font-size: 13px;
      }
      
      .cohort-date {
        font-weight: 600;
        color: #1f2937;
      }
      
      .new-users {
        color: #6b7280;
      }
      
      .rate-value {
        font-weight: 600;
      }
      
      .rate-high {
        background: rgba(16, 185, 129, 0.15);
        .rate-value { color: #059669; }
      }
      
      .rate-medium {
        background: rgba(59, 130, 246, 0.15);
        .rate-value { color: #2563eb; }
      }
      
      .rate-low {
        background: rgba(251, 191, 36, 0.15);
        .rate-value { color: #d97706; }
      }
      
      .rate-very-low {
        background: rgba(239, 68, 68, 0.1);
        .rate-value { color: #dc2626; }
      }
    }
  }
}
</style>
