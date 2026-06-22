<script setup>
import { ref, onMounted, computed } from 'vue'
import { useRouter } from 'vue-router'
import { useUserStore } from '@/store/user'
import { statsApi, reportApi, summaryApi } from '@/api'
import VChart from 'vue-echarts'
import * as echarts from 'echarts'
import { use } from 'echarts/core'
import { CanvasRenderer } from 'echarts/renderers'
import { LineChart, BarChart, PieChart } from 'echarts/charts'
import { TitleComponent, TooltipComponent, LegendComponent, GridComponent } from 'echarts/components'

use([CanvasRenderer, LineChart, BarChart, PieChart, TitleComponent, TooltipComponent, LegendComponent, GridComponent])

const userStore = useUserStore()
const router = useRouter()

const overview = ref({})
const trend = ref([])
const ranking = ref([])
const pendingUsers = ref([])
const loading = ref(false)

async function loadAll() {
  loading.value = true
  try {
    const [ov, tr, rk, pd] = await Promise.all([
      statsApi.overview(),
      statsApi.trend(8),
      statsApi.teamRanking(),
      reportApi.pendingUsers().catch(() => ({ pending_users: [] }))
    ])
    overview.value = ov
    trend.value = tr.data || []
    ranking.value = rk
    pendingUsers.value = pd.pending_users || []
  } finally {
    loading.value = false
  }
}

const trendOption = computed(() => ({
  tooltip: { trigger: 'axis' },
  legend: { data: ['提交率(%)'] },
  grid: { left: 40, right: 20, top: 40, bottom: 30 },
  xAxis: { type: 'category', data: trend.value.map(d => d.week_key), axisLabel: { fontSize: 11 } },
  yAxis: { type: 'value', max: 100, axisLabel: { formatter: '{value}%' } },
  series: [{
    name: '提交率(%)', type: 'line', smooth: true, symbol: 'circle', symbolSize: 8,
    data: trend.value.map(d => d.submission_rate),
    itemStyle: { color: '#409eff' },
    areaStyle: { color: new echarts.graphic.LinearGradient(0, 0, 0, 1, [
      { offset: 0, color: 'rgba(64,158,255,0.4)' }, { offset: 1, color: 'rgba(64,158,255,0.05)' }
    ]) },
    markLine: { data: [{ type: 'average', name: '平均' }] }
  }]
}))

const rankingOption = computed(() => ({
  tooltip: { trigger: 'axis' },
  grid: { left: 100, right: 40, top: 20, bottom: 30 },
  xAxis: { type: 'value', max: 100, axisLabel: { formatter: '{value}%' } },
  yAxis: { type: 'category', data: ranking.value.slice(0, 10).map(d => d.team_name).reverse() },
  series: [{
    type: 'bar',
    data: ranking.value.slice(0, 10).map(d => ({
      value: d.submission_rate,
      itemStyle: { color: d.submission_rate >= 90 ? '#67c23a' : d.submission_rate >= 70 ? '#e6a23c' : '#f56c6c' }
    })).reverse(),
    label: { show: true, position: 'right', formatter: '{c}%' },
    barWidth: 18
  }]
}))

onMounted(loadAll)
</script>

<template>
  <div>
    <div class="stat-grid">
      <div class="stat-card">
        <div class="stat-num">{{ overview.total_users || 0 }}</div>
        <div class="stat-label">总人数</div>
      </div>
      <div class="stat-card success">
        <div class="stat-num">{{ overview.submitted_count || 0 }}</div>
        <div class="stat-label">本周已提交</div>
      </div>
      <div class="stat-card danger">
        <div class="stat-num">{{ overview.pending_count || 0 }}</div>
        <div class="stat-label">本周待提交</div>
      </div>
      <div class="stat-card warning">
        <div class="stat-num">{{ overview.submission_rate || 0 }}<span style="font-size:18px;">%</span></div>
        <div class="stat-label">当前提交率</div>
      </div>
      <div class="stat-card purple">
        <div class="stat-num">{{ overview.average_word_count || 0 }}</div>
        <div class="stat-label">人均字数</div>
      </div>
      <div class="stat-card info">
        <div class="stat-num">{{ overview.total_words || 0 }}</div>
        <div class="stat-label">总字数</div>
      </div>
    </div>

    <el-row :gutter="16">
      <el-col :span="14">
        <div class="card">
          <div class="flex-between mb-10">
            <h3 style="font-size:15px;">📈 近8周提交率趋势</h3>
            <el-tag size="small" type="info">平均提交率</el-tag>
          </div>
          <v-chart class="chart" :option="trendOption" autoresize style="height:320px;" />
        </div>
      </el-col>
      <el-col :span="10">
        <div class="card">
          <div class="flex-between mb-10">
            <h3 style="font-size:15px;">🏆 本周团队提交率排行</h3>
          </div>
          <v-chart class="chart" :option="rankingOption" autoresize style="height:320px;" />
        </div>
      </el-col>
    </el-row>

    <el-row :gutter="16" class="mt-20">
      <el-col :span="12">
        <div class="card">
          <div class="flex-between mb-10">
            <h3 style="font-size:15px;">⚠️ 待提交成员 ({{ pendingUsers.length }}人)</h3>
            <el-button size="small" type="primary" :disabled="!userStore.isAdmin" @click="router.push('/report/list')">查看全部</el-button>
          </div>
          <el-empty v-if="!pendingUsers.length" description="本周全部提交完成 🎉" :image-size="80" />
          <el-table v-else :data="pendingUsers" size="small" stripe max-height="260">
            <el-table-column prop="user_name" label="姓名" width="100" />
            <el-table-column prop="team_name" label="团队" width="140" />
            <el-table-column prop="email" label="邮箱" />
          </el-table>
        </div>
      </el-col>
      <el-col :span="12">
        <div class="card">
          <div class="flex-between mb-10">
            <h3 style="font-size:15px;">💡 快捷操作</h3>
          </div>
          <div style="display:grid;grid-template-columns:repeat(3,1fr);gap:12px;">
            <el-button type="primary" @click="router.push('/report/write')" style="height:64px;font-size:15px;">
              <el-icon style="font-size:18px;"><Edit /></el-icon><br/>填写周报
            </el-button>
            <el-button type="success" @click="router.push('/summary')" style="height:64px;font-size:15px;">
              <el-icon style="font-size:18px;"><CollectionTag /></el-icon><br/>查看汇总
            </el-button>
            <el-button type="warning" @click="router.push('/statistics')" style="height:64px;font-size:15px;">
              <el-icon style="font-size:18px;"><TrendCharts /></el-icon><br/>统计面板
            </el-button>
            <el-button v-if="userStore.isAdmin" @click="router.push('/templates')" style="height:64px;font-size:15px;">
              <el-icon style="font-size:18px;"><Document /></el-icon><br/>模板管理
            </el-button>
            <el-button v-if="userStore.isAdmin" type="danger" @click="router.push('/export')" style="height:64px;font-size:15px;">
              <el-icon style="font-size:18px;"><Promotion /></el-icon><br/>导出分发
            </el-button>
            <el-button v-if="userStore.isAdmin" @click="router.push('/users')" style="height:64px;font-size:15px;">
              <el-icon style="font-size:18px;"><Avatar /></el-icon><br/>用户管理
            </el-button>
          </div>
        </div>
      </el-col>
    </el-row>
  </div>
</template>
