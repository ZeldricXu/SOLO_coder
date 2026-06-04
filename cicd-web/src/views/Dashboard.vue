<template>
  <div class="dashboard">
    <div class="page-header">
      <h2 class="page-title">仪表盘</h2>
      <el-radio-group v-model="timeRange" size="default" @change="loadData">
        <el-radio-button label="24h">最近24小时</el-radio-button>
        <el-radio-button label="7d">最近7天</el-radio-button>
        <el-radio-button label="30d">最近30天</el-radio-button>
      </el-radio-group>
    </div>

    <el-row :gutter="20">
      <el-col :span="6">
        <div class="metric-card info">
          <div class="metric-value">{{ stats.totalBuilds || 0 }}</div>
          <div class="metric-label">构建次数</div>
        </div>
      </el-col>
      <el-col :span="6">
        <div class="metric-card success">
          <div class="metric-value">{{ stats.successRate || 0 }}%</div>
          <div class="metric-label">构建成功率</div>
        </div>
      </el-col>
      <el-col :span="6">
        <div class="metric-card">
          <div class="metric-value">{{ stats.avgDurationSeconds || 0 }}s</div>
          <div class="metric-label">平均构建耗时</div>
        </div>
      </el-col>
      <el-col :span="6">
        <div class="metric-card warning">
          <div class="metric-value">{{ dora.changeFailureRate || 0 }}%</div>
          <div class="metric-label">变更失败率</div>
        </div>
      </el-col>
    </el-row>

    <el-row :gutter="20" style="margin-top: 20px">
      <el-col :span="16">
        <div class="card">
          <h3 class="card-title">构建趋势</h3>
          <v-chart class="chart" :option="buildTrendOption" autoresize />
        </div>
      </el-col>
      <el-col :span="8">
        <div class="card">
          <h3 class="card-title">各阶段耗时分布</h3>
          <v-chart class="chart" :option="stageDurationOption" autoresize />
        </div>
      </el-col>
    </el-row>

    <el-row :gutter="20" style="margin-top: 20px">
      <el-col :span="12">
        <div class="card">
          <h3 class="card-title">部署频率</h3>
          <v-chart class="chart" :option="deploymentTrendOption" autoresize />
        </div>
      </el-col>
      <el-col :span="12">
        <div class="card">
          <h3 class="card-title">各环境运行版本</h3>
          <el-table :data="environmentVersions" size="default">
            <el-table-column prop="environmentName" label="环境" width="120" />
            <el-table-column prop="serviceName" label="服务" width="150" />
            <el-table-column prop="version" label="版本" />
            <el-table-column prop="deployedAt" label="部署时间">
              <template #default="{ row }">
                {{ formatTime(row.deployedAt) }}
              </template>
            </el-table-column>
            <el-table-column prop="deployedBy" label="部署人" width="120" />
            <el-table-column prop="status" label="状态">
              <template #default="{ row }">
                <span :class="['status-tag', 'status-' + row.status?.toLowerCase()]">{{ row.status }}</span>
              </template>
            </el-table-column>
          </el-table>
        </div>
      </el-col>
    </el-row>

    <el-row :gutter="20" style="margin-top: 20px">
      <el-col :span="12">
        <div class="card">
          <h3 class="card-title">DORA 指标</h3>
          <el-descriptions :column="2" border size="default">
            <el-descriptions-item label="部署频率">
              <span style="font-size: 24px; font-weight: 600; color: #409eff">
                {{ dora.deploymentFrequency || 0 }} 次/天
              </span>
            </el-descriptions-item>
            <el-descriptions-item label="变更失败率">
              <span style="font-size: 24px; font-weight: 600" :class="{ 'text-success': (dora.changeFailureRate || 0) < 15, 'text-danger': (dora.changeFailureRate || 0) >= 15 }">
                {{ dora.changeFailureRate || 0 }}%
              </span>
            </el-descriptions-item>
            <el-descriptions-item label="总部署次数">
              {{ dora.totalDeployments || 0 }}
            </el-descriptions-item>
            <el-descriptions-item label="回滚次数">
              {{ dora.rollbacks || 0 }}
            </el-descriptions-item>
          </el-descriptions>
        </div>
      </el-col>
      <el-col :span="12">
        <div class="card">
          <h3 class="card-title">最近构建</h3>
          <el-table :data="recentExecutions" size="default">
            <el-table-column prop="pipelineName" label="流水线" />
            <el-table-column prop="executionNumber" label="编号" width="80">
              <template #default="{ row }">#{{ row.executionNumber }}</template>
            </el-table-column>
            <el-table-column prop="branchName" label="分支" width="120" />
            <el-table-column prop="status" label="状态" width="100">
              <template #default="{ row }">
                <span :class="['status-tag', 'status-' + row.status?.toLowerCase()]">{{ getStatusText(row.status) }}</span>
              </template>
            </el-table-column>
            <el-table-column prop="durationSeconds" label="耗时">
              <template #default="{ row }">{{ row.durationSeconds }}s</template>
            </el-table-column>
            <el-table-column label="操作" width="80">
              <template #default="{ row }">
                <el-button type="primary" link @click="viewExecution(row)">详情</el-button>
              </template>
            </el-table-column>
          </el-table>
        </div>
      </el-col>
    </el-row>
  </div>
</template>

<script setup>
import { ref, onMounted, computed } from 'vue'
import { useRouter } from 'vue-router'
import { use } from 'echarts/core'
import { CanvasRenderer } from 'echarts/renderers'
import { BarChart, LineChart, PieChart } from 'echarts/charts'
import {
  TitleComponent,
  TooltipComponent,
  LegendComponent,
  GridComponent,
  DatasetComponent
} from 'echarts/components'
import VChart from 'vue-echarts'
import { dashboardAPI, pipelineAPI } from '@/api'
import { useUserStore } from '@/store/user'
import dayjs from 'dayjs'

use([
  CanvasRenderer,
  BarChart,
  LineChart,
  PieChart,
  TitleComponent,
  TooltipComponent,
  LegendComponent,
  GridComponent,
  DatasetComponent
])

const router = useRouter()
const userStore = useUserStore()

const timeRange = ref('24h')
const stats = ref({})
const dora = ref({})
const environmentVersions = ref([])
const recentExecutions = ref([])

const loadData = async () => {
  const projectId = userStore.currentProject.id
  try {
    const [overview, pipelineStats, doraMetrics, envVersions, executions] = await Promise.all([
      dashboardAPI.overview(projectId),
      dashboardAPI.pipelineStats(projectId, timeRange.value),
      dashboardAPI.doraMetrics(projectId, timeRange.value),
      dashboardAPI.environmentVersions(projectId),
      pipelineAPI.listExecutions(1, { size: 5 })
    ])
    stats.value = pipelineStats
    dora.value = doraMetrics
    environmentVersions.value = envVersions
    recentExecutions.value = executions.content || executions || []
  } catch (e) {
    console.error('Failed to load dashboard data', e)
  }
}

const buildTrendOption = computed(() => {
  const trend = stats.value.buildTrend || []
  const times = trend.map(t => dayjs(t.time).format('HH:mm'))
  const success = trend.map(t => t.success || 0)
  const failed = trend.map(t => t.failed || 0)

  return {
    tooltip: { trigger: 'axis' },
    legend: { data: ['成功', '失败'] },
    grid: { left: '3%', right: '4%', bottom: '3%', containLabel: true },
    xAxis: { type: 'category', data: times },
    yAxis: { type: 'value' },
    series: [
      { name: '成功', type: 'line', smooth: true, data: success, itemStyle: { color: '#67c23a' }, areaStyle: { opacity: 0.3 } },
      { name: '失败', type: 'line', smooth: true, data: failed, itemStyle: { color: '#f56c6c' }, areaStyle: { opacity: 0.3 } }
    ]
  }
})

const stageDurationOption = computed(() => {
  const distribution = stats.value.stageDurationDistribution || {}
  const stages = Object.keys(distribution)
  const durations = Object.values(distribution)

  return {
    tooltip: { trigger: 'axis', formatter: '{b}: {c}s' },
    grid: { left: '3%', right: '4%', bottom: '3%', containLabel: true },
    xAxis: { type: 'category', data: stages, axisLabel: { rotate: 45 } },
    yAxis: { type: 'value', name: '秒' },
    series: [{
      type: 'bar',
      data: durations,
      itemStyle: {
        color: {
          type: 'linear',
          x: 0, y: 0, x2: 0, y2: 1,
          colorStops: [
            { offset: 0, color: '#667eea' },
            { offset: 1, color: '#764ba2' }
          ]
        }
      }
    }]
  }
})

const deploymentTrendOption = computed(() => {
  const trend = dora.value.deploymentTrend || []
  const times = trend.map(t => dayjs(t.time).format('MM-DD'))
  const success = trend.map(t => t.success || 0)
  const failed = trend.map(t => t.failed || 0)

  return {
    tooltip: { trigger: 'axis' },
    legend: { data: ['成功', '失败'] },
    grid: { left: '3%', right: '4%', bottom: '3%', containLabel: true },
    xAxis: { type: 'category', data: times },
    yAxis: { type: 'value' },
    series: [
      { name: '成功', type: 'bar', data: success, itemStyle: { color: '#67c23a' } },
      { name: '失败', type: 'bar', data: failed, itemStyle: { color: '#f56c6c' } }
    ]
  }
})

const getStatusText = (status) => {
  const map = {
    'SUCCESS': '成功',
    'FAILED': '失败',
    'RUNNING': '运行中',
    'PENDING': '等待中',
    'CANCELLED': '已取消'
  }
  return map[status] || status
}

const formatTime = (time) => {
  return time ? dayjs(time).format('YYYY-MM-DD HH:mm') : '-'
}

const viewExecution = (row) => {
  router.push(`/pipelines/${row.pipelineId}/executions/${row.id}`)
}

onMounted(() => {
  loadData()
})
</script>

<style scoped lang="scss">
.dashboard {
  padding: 20px;
}

.card-title {
  font-size: 16px;
  font-weight: 600;
  margin-bottom: 16px;
  color: #303133;
}

.chart {
  height: 300px;
}

.text-success {
  color: #67c23a;
}

.text-danger {
  color: #f56c6c;
}
</style>
