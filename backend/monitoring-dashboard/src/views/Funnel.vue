<template>
  <div class="funnel-page">
    <div class="page-header">
      <h1>付费漏斗分析</h1>
      <div class="header-actions">
        <el-select v-model="funnelType" style="width: 200px; margin-right: 16px">
          <el-option label="注册到付费" value="signup_to_pay" />
          <el-option label="首次游戏到付费" value="game_to_pay" />
          <el-option label="浏览到购买" value="browse_to_purchase" />
        </el-select>
        <el-date-picker
          v-model="dateRange"
          type="daterange"
          range-separator="至"
          start-placeholder="开始日期"
          end-placeholder="结束日期"
          style="width: 300px; margin-right: 16px"
        />
        <el-button type="primary" @click="loadFunnelData">
          <el-icon><Search /></el-icon>
          查询
        </el-button>
      </div>
    </div>
    
    <el-row :gutter="20">
      <el-col :span="8">
        <el-card class="summary-card">
          <template #header>
            <span>漏斗概览</span>
          </template>
          <div class="summary-stats">
            <div class="stat-item">
              <div class="stat-value">{{ funnelData.overall_conversion }}%</div>
              <div class="stat-label">整体转化率</div>
            </div>
            <div class="stat-item">
              <div class="stat-value">{{ funnelData.avg_conversion_per_step }}%</div>
              <div class="stat-label">平均每步转化</div>
            </div>
          </div>
          <el-divider />
          <div class="summary-details">
            <div class="detail-row">
              <span class="label">总入口用户</span>
              <span class="value">{{ funnelData.funnel_steps?.[0]?.count || 0 }}</span>
            </div>
            <div class="detail-row">
              <span class="label">最终转化用户</span>
              <span class="value">{{ funnelData.funnel_steps?.[funnelData.funnel_steps.length - 1]?.count || 0 }}</span>
            </div>
            <div class="detail-row highlight">
              <span class="label">流失用户</span>
              <span class="value loss">
                {{ (funnelData.funnel_steps?.[0]?.count || 0) - (funnelData.funnel_steps?.[funnelData.funnel_steps.length - 1]?.count || 0) }}
              </span>
            </div>
          </div>
        </el-card>
        
        <el-card class="dropoff-card" style="margin-top: 20px">
          <template #header>
            <span>主要流失点</span>
          </template>
          <div class="dropoff-list">
            <div
              v-for="(item, index) in funnelData.drop_off_points"
              :key="index"
              class="dropoff-item"
            >
              <div class="dropoff-info">
                <div class="dropoff-path">
                  <span class="from-step">{{ item.from }}</span>
                  <el-icon class="arrow"><ArrowRight /></el-icon>
                  <span class="to-step">{{ item.to }}</span>
                </div>
                <div class="dropoff-count">
                  流失 <span class="count">{{ item.drop_count }}</span> 人
                </div>
              </div>
              <el-progress
                :percentage="getDropoffPercentage(item)"
                :color="getDropoffColor(getDropoffPercentage(item))"
                :stroke-width="8"
                :show-text="false"
              />
            </div>
          </div>
        </el-card>
      </el-col>
      
      <el-col :span="16">
        <el-card class="chart-card">
          <template #header>
            <div class="card-header">
              <span>付费转化漏斗</span>
              <el-radio-group v-model="funnelView" size="small">
                <el-radio-button value="count">用户数</el-radio-button>
                <el-radio-button value="rate">转化率</el-radio-button>
              </el-radio-group>
            </div>
          </template>
          <div ref="funnelChartRef" class="chart-container"></div>
        </el-card>
        
        <el-card class="chart-card" style="margin-top: 20px">
          <template #header>
            <span>转化趋势对比</span>
          </template>
          <div ref="comparisonChartRef" class="chart-container"></div>
        </el-card>
      </el-col>
    </el-row>
    
    <el-card class="details-card" style="margin-top: 20px">
      <template #header>
        <span>漏斗详情</span>
      </template>
      <el-table :data="funnelData.funnel_steps" style="width: 100%" border>
        <el-table-column type="index" label="步骤" width="60" align="center" />
        <el-table-column prop="step" label="转化步骤" min-width="180" />
        <el-table-column prop="count" label="用户数" min-width="120" align="right">
          <template #default="{ row }">
            <span class="count-text">{{ formatNumber(row.count) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="累计转化率" min-width="150" align="right">
          <template #default="{ row, $index }">
            <el-tag :type="getConversionTagType(row.conversion_rate)" size="large">
              {{ row.conversion_rate }}%
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="转化率趋势" min-width="200">
          <template #default="{ row, $index }">
            <el-progress
              :percentage="row.conversion_rate"
              :color="getProgressColor(row.conversion_rate)"
              :stroke-width="20"
            />
          </template>
        </el-table-column>
        <el-table-column label="对比上期" min-width="120" align="center">
          <template #default="{ row, $index }">
            <div class="comparison-badge">
              <el-icon v-if="getComparisonValue($index) >= 0" class="up-icon"><ArrowUp /></el-icon>
              <el-icon v-else class="down-icon"><ArrowDown /></el-icon>
              <span :class="{ 'text-green': getComparisonValue($index) >= 0, 'text-red': getComparisonValue($index) < 0 }">
                {{ Math.abs(getComparisonValue($index)) }}%
              </span>
            </div>
          </template>
        </el-table-column>
      </el-table>
    </el-card>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, onUnmounted } from 'vue'
import * as echarts from 'echarts'
import dayjs from 'dayjs'
import { analysisApi } from '@/api'

const funnelType = ref('signup_to_pay')
const dateRange = ref([
  dayjs().subtract(7, 'day').toDate(), dayjs().toDate()
])
const funnelView = ref('count')

const funnelData = ref({
  funnel_steps: [
    { step: '注册', count: 10000, conversion_rate: 100 },
    { step: '首次登录', count: 8500, conversion_rate: 85 },
    { step: '完成新手引导', count: 7200, conversion_rate: 84.7 },
    { step: '首次游戏', count: 6500, conversion_rate: 90.3 },
    { step: '首次付费', count: 650, conversion_rate: 10.0 }
  ],
  drop_off_points: [
    { from: '注册', to: '首次登录', drop_count: 1500 },
    { from: '首次登录', to: '完成新手引导', drop_count: 1300 },
    { from: '首次游戏', to: '首次付费', drop_count: 5850 }
  ],
  overall_conversion: 6.5,
  avg_conversion_per_step: 74.0
})

const previousPeriodData = [95, 80, 82, 88, 9.5]

const funnelChartRef = ref(null)
const comparisonChartRef = ref(null)

let funnelChart = null
let comparisonChart = null

const formatNumber = (num) => {
  return num.toLocaleString()
}

const getDropoffPercentage = (item) => {
  const total = funnelData.value.funnel_steps?.[0]?.count || 1
  return Math.round((item.drop_count / total) * 100)
}

const getDropoffColor = (percentage) => {
  if (percentage >= 30) return '#ef4444'
  if (percentage >= 15) return '#f59e0b'
  return '#10b981'
}

const getConversionTagType = (rate) => {
  if (rate >= 80) return 'success'
  if (rate >= 50) return 'primary'
  if (rate >= 20) return 'warning'
  return 'danger'
}

const getProgressColor = (rate) => {
  if (rate >= 80) return '#10b981'
  if (rate >= 50) return '#3b82f6'
  if (rate >= 20) return '#f59e0b'
  return '#ef4444'
}

const getComparisonValue = (index) => {
  const current = funnelData.value.funnel_steps[index]?.conversion_rate || 0
  const previous = previousPeriodData[index] || 0
  return Math.round((current - previous) * 100) / 100
}

const initFunnelChart = () => {
  const chart = echarts.init(funnelChartRef.value)
  
  const updateChart = () => {
    if (funnelView.value === 'count') {
      chart.setOption({
        tooltip: {
          trigger: 'item',
          formatter: '{b}: {c}人 ({d}%)'
        },
        series: [
          {
            name: '用户数',
            type: 'funnel',
            left: '10%',
            top: 60,
            bottom: 60,
            width: '80%',
            min: 0,
            max: 10000,
            minSize: '0%',
            maxSize: '100%',
            sort: 'descending',
            gap: 2,
            label: {
              show: true,
              position: 'inside',
              formatter: '{b}\n{c}人',
              fontSize: 14,
              fontWeight: 600,
              color: '#fff'
            },
            labelLine: {
              length: 10,
              lineStyle: {
                width: 1,
                type: 'solid'
              }
            },
            itemStyle: {
              borderColor: '#fff',
              borderWidth: 1
            },
            emphasis: {
              label: {
                fontSize: 16
              }
            },
            data: funnelData.value.funnel_steps.map((item, index) => ({
              value: item.count,
              name: item.step,
              itemStyle: {
                color: ['#3b82f6', '#8b5cf6', '#06b6d4', '#10b981', '#f59e0b'][index]
              }
            }))
          }
        ]
      })
    } else {
      chart.setOption({
        tooltip: {
          trigger: 'item',
          formatter: '{b}: {c}%'
        },
        xAxis: {
          type: 'value',
          max: 100,
          axisLabel: {
            formatter: '{value}%'
          }
        },
        yAxis: {
          type: 'category',
          data: funnelData.value.funnel_steps.map(item => item.step).reverse()
        },
        series: [
          {
            name: '转化率',
            type: 'bar',
            barWidth: '60%',
            label: {
              show: true,
              position: 'right',
              formatter: '{c}%',
              fontWeight: 600
            },
            itemStyle: {
              borderRadius: [0, 4, 4, 0]
            },
            data: funnelData.value.funnel_steps.map((item, index) => ({
              value: item.conversion_rate,
              itemStyle: {
                color: ['#3b82f6', '#8b5cf6', '#06b6d4', '#10b981', '#f59e0b'][index]
              }
            })).reverse()
          }
        ]
      })
    }
  }
  
  updateChart()
  funnelChart = { chart, update: updateChart }
}

const initComparisonChart = () => {
  const chart = echarts.init(comparisonChartRef.value)
  
  chart.setOption({
    tooltip: {
      trigger: 'axis'
    },
    legend: {
      data: ['本期', '上期'],
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
      data: funnelData.value.funnel_steps.map(item => item.step)
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
        name: '本期',
        type: 'line',
        smooth: true,
        symbol: 'circle',
        symbolSize: 10,
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
        data: funnelData.value.funnel_steps.map(item => item.conversion_rate)
      },
      {
        name: '上期',
        type: 'line',
        smooth: true,
        symbol: 'circle',
        symbolSize: 10,
        lineStyle: {
          width: 3,
          color: '#f59e0b',
          type: 'dashed'
        },
        itemStyle: {
          color: '#f59e0b'
        },
        data: previousPeriodData
      }
    ]
  })
  
  comparisonChart = chart
}

const loadFunnelData = async () => {
  try {
    const data = await analysisApi.getFunnelAnalysis({
      funnel_type: funnelType.value,
      start_date: dayjs(dateRange.value[0]).format('YYYY-MM-DD'),
      end_date: dayjs(dateRange.value[1]).format('YYYY-MM-DD')
    })
    funnelData.value = { ...funnelData.value, ...data }
    
    if (funnelChart) {
      funnelChart.update()
    }
  } catch (e) {
    console.error('Failed to load funnel data:', e)
  }
}

const unwatchFunnelView = watch(funnelView, () => {
  if (funnelChart) {
    funnelChart.update()
  }
})

onMounted(() => {
  initFunnelChart()
  initComparisonChart()
})

onUnmounted(() => {
  unwatchFunnelView?.()
  funnelChart?.chart?.dispose()
  comparisonChart?.dispose()
})
</script>

<style lang="scss" scoped>
.funnel-page {
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
  
  .summary-card {
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
    
    .summary-stats {
      display: flex;
      justify-content: space-around;
      padding: 16px 0;
      
      .stat-item {
        text-align: center;
        
        .stat-value {
          font-size: 36px;
          font-weight: 700;
          color: #3b82f6;
          margin-bottom: 4px;
        }
        
        .stat-label {
          font-size: 13px;
          color: #6b7280;
        }
      }
    }
    
    .summary-details {
      padding: 8px 0;
      
      .detail-row {
        display: flex;
        justify-content: space-between;
        padding: 8px 0;
        font-size: 14px;
        
        .label {
          color: #6b7280;
        }
        
        .value {
          color: #1f2937;
          font-weight: 600;
          
          &.loss {
            color: #ef4444;
          }
        }
        
        &.highlight {
          padding-top: 16px;
          border-top: 1px dashed #e5e7eb;
        }
      }
    }
  }
  
  .dropoff-card {
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
    
    .dropoff-list {
      .dropoff-item {
        padding: 16px 0;
        border-bottom: 1px solid #f3f4f6;
        
        &:last-child {
          border-bottom: none;
        }
        
        .dropoff-info {
          margin-bottom: 12px;
          
          .dropoff-path {
            display: flex;
            align-items: center;
            margin-bottom: 4px;
            
            .from-step {
              color: #1f2937;
              font-weight: 600;
            }
            
            .arrow {
              color: #9ca3af;
              margin: 0 8px;
            }
            
            .to-step {
              color: #6b7280;
            }
          }
          
          .dropoff-count {
            font-size: 13px;
            color: #6b7280;
            
            .count {
              color: #ef4444;
              font-weight: 600;
              margin: 0 4px;
            }
          }
        }
      }
    }
  }
  
  .chart-card {
    border: none;
    border-radius: 12px;
    height: 100%;
    
    :deep(.el-card__header) {
      border-bottom: 1px solid #f3f4f6;
      
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
    height: 320px;
    width: 100%;
  }
  
  .details-card {
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
    
    :deep(.el-table) {
      .count-text {
        font-family: 'SF Mono', Monaco, monospace;
        font-weight: 600;
        color: #1f2937;
      }
      
      .comparison-badge {
        display: flex;
        align-items: center;
        justify-content: center;
        gap: 2px;
        
        .up-icon {
          color: #10b981;
        }
        
        .down-icon {
          color: #ef4444;
        }
        
        .text-green {
          color: #10b981;
          font-weight: 600;
        }
        
        .text-red {
          color: #ef4444;
          font-weight: 600;
        }
      }
    }
  }
}
</style>
