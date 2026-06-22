<script setup>
import { ref, onMounted, computed } from 'vue'
import { statsApi } from '@/api'
import dayjs from 'dayjs'
import VChart from 'vue-echarts'

const loading = ref(false)
const currentWeek = ref('')
const overview = ref({})
const trend = ref([])
const ranking = ref([])
const personal = ref({})
const wordCloud = ref([])
const reminderLogs = ref([])

function generateWeekOptions() {
  const opts = []
  const today = dayjs()
  for (let i = 0; i < 8; i++) {
    const d = today.subtract(i, 'week')
    const monday = d.startOf('week').add(1, 'day')
    const weekKey = `${monday.year()}-W${String(monday.isoWeek()).padStart(2,'0')}`
    const friday = monday.add(4, 'day')
    opts.push({
      value: weekKey,
      label: `${monday.format('MM月DD日')}-${friday.format('MM月DD日')} (第${monday.isoWeek()}周)`
    })
  }
  return opts
}
const weekOptions = generateWeekOptions()
currentWeek.value = weekOptions[0]?.value

async function loadAll() {
  loading.value = true
  try {
    const [ov, tr, rk, ps, wc, rl] = await Promise.all([
      statsApi.overview(currentWeek.value).catch(() => ({})),
      statsApi.trend(12).catch(() => ({ data: [] })),
      statsApi.teamRanking(currentWeek.value).catch(() => []),
      statsApi.personal({ weeks: 12 }).catch(() => ({})),
      statsApi.wordCloud(currentWeek.value).catch(() => ({ words: [] })),
      statsApi.reminderLogs({ week_key: currentWeek.value, limit: 50 }).catch(() => [])
    ])
    overview.value = ov
    trend.value = tr.data || []
    ranking.value = rk
    personal.value = ps
    wordCloud.value = wc.words || []
    reminderLogs.value = rl
  } finally {
    loading.value = false
  }
}

const trendOption = computed(() => ({
  tooltip: { trigger: 'axis' },
  legend: { data: ['提交人数', '提交率(%)'] },
  grid: { left: 50, right: 60, top: 40, bottom: 40 },
  xAxis: { type: 'category', data: trend.value.map(d => d.week_key), axisLabel: { fontSize: 11, rotate: 30 } },
  yAxis: [
    { type: 'value', name: '人数', position: 'left' },
    { type: 'value', name: '提交率%', position: 'right', max: 100, axisLabel: { formatter: '{value}%' } }
  ],
  series: [
    {
      name: '提交人数', type: 'bar', data: trend.value.map(d => d.submitted_count),
      itemStyle: { color: '#67c23a' }, barWidth: 24
    },
    {
      name: '提交率(%)', type: 'line', yAxisIndex: 1, smooth: true, symbol: 'circle', symbolSize: 8,
      data: trend.value.map(d => d.submission_rate),
      itemStyle: { color: '#409eff' },
      lineStyle: { width: 3 }
    }
  ]
}))

const rankingOption = computed(() => ({
  tooltip: { trigger: 'axis', axisPointer: { type: 'shadow' } },
  legend: { data: ['已提交', '未提交'] },
  grid: { left: 100, right: 40, top: 40, bottom: 30 },
  xAxis: { type: 'value' },
  yAxis: { type: 'category', data: ranking.value.map(d => d.team_name) },
  series: [
    {
      name: '已提交', type: 'bar', stack: 'total',
      data: ranking.value.map(d => d.submitted_count),
      itemStyle: { color: '#67c23a' },
      label: { show: true, position: 'insideLeft', color: '#fff' }
    },
    {
      name: '未提交', type: 'bar', stack: 'total',
      data: ranking.value.map(d => Math.max(0, d.total_members - d.submitted_count)),
      itemStyle: { color: '#f56c6c' }
    }
  ]
}))

const wordCloudOption = computed(() => {
  const maxVal = Math.max(...wordCloud.value.slice(0, 50).map(w => w.count), 1)
  return {
    tooltip: { show: true },
    series: [{
      type: 'graph',
      layout: 'none',
      roam: false,
      symbolSize: (val) => 14 + Math.min(46, val[2] / maxVal * 46),
      data: wordCloud.value.slice(0, 60).map((w, i) => {
        const angle = i * 0.7
        const radius = 40 + (i % 5) * 30
        return {
          name: w.word,
          value: w.count,
          symbolSize: 14 + Math.min(40, w.count / maxVal * 40),
          x: 350 + Math.cos(angle) * radius,
          y: 200 + Math.sin(angle) * radius,
          itemStyle: {
            color: `hsl(${(i * 37) % 360}, 65%, ${45 + (i%4)*8}%)`
          },
          label: {
            show: true,
            formatter: w.word,
            fontSize: 12 + Math.min(24, w.count / maxVal * 24),
            fontWeight: 600
          }
        }
      })
    }]
  }
})

const personalOption = computed(() => {
  const data = personal.value.weekly_data || []
  return {
    tooltip: { trigger: 'axis' },
    grid: { left: 50, right: 40, top: 40, bottom: 40 },
    xAxis: { type: 'category', data: data.map(d => d.week_key), axisLabel: { fontSize: 11, rotate: 30 } },
    yAxis: [
      { type: 'value', name: '字数', position: 'left' },
      { type: 'value', name: '状态', position: 'right', max: 1.2, axisLabel: {
        formatter: (v) => v >= 1 ? '✅已交' : (v >= 0.5 ? '📝草稿' : '❌未交')
      } }
    ],
    series: [
      {
        name: '字数', type: 'bar',
        data: data.map(d => d.word_count),
        itemStyle: {
          color: (params) => {
            const status = data[params.dataIndex].status
            if (status === 'submitted') return '#409eff'
            if (status === 'draft') return '#e6a23c'
            return '#909399'
          }
        },
        barWidth: 20
      },
      {
        name: '状态', type: 'scatter', yAxisIndex: 1,
        symbolSize: 14,
        data: data.map(d => d.submitted ? 1 : (d.status === 'draft' ? 0.5 : 0)),
        itemStyle: {
          color: (params) => {
            const val = params.value
            return val >= 1 ? '#67c23a' : val >= 0.5 ? '#e6a23c' : '#f56c6c'
          }
        }
      }
    ]
  }
})

onMounted(loadAll)
</script>

<template>
  <div class="page-container" v-loading="loading">
    <div class="page-header">
      <div>
        <h2>📊 统计面板</h2>
        <div class="text-sm text-gray mt-10">数据驱动 · 洞察团队</div>
      </div>
      <el-select v-model="currentWeek" @change="loadAll" style="width:220px;">
        <el-option v-for="w in weekOptions" :key="w.value" :label="w.label" :value="w.value" />
      </el-select>
    </div>

    <div class="stat-grid">
      <div class="stat-card">
        <div class="stat-num">{{ overview.total_users || 0 }}</div>
        <div class="stat-label">总人数</div>
      </div>
      <div class="stat-card success">
        <div class="stat-num">{{ overview.submitted_count || 0 }}</div>
        <div class="stat-label">本周已提交</div>
      </div>
      <div class="stat-card warning">
        <div class="stat-num">{{ overview.submission_rate || 0 }}<span style="font-size:18px;">%</span></div>
        <div class="stat-label">本周提交率</div>
      </div>
      <div class="stat-card purple">
        <div class="stat-num">{{ overview.average_word_count || 0 }}</div>
        <div class="stat-label">人均字数</div>
      </div>
      <div class="stat-card info">
        <div class="stat-num">{{ personal.submission_rate || 0 }}<span style="font-size:18px;">%</span></div>
        <div class="stat-label">我的提交率</div>
      </div>
      <div class="stat-card">
        <div class="stat-num">{{ personal.average_word_count || 0 }}</div>
        <div class="stat-label">我的人均字数</div>
      </div>
    </div>

    <el-row :gutter="16" class="mt-20">
      <el-col :span="14">
        <div class="card">
          <h3 style="margin-bottom:12px;">📈 近12周整体趋势</h3>
          <v-chart :option="trendOption" autoresize style="height:360px;" />
        </div>
      </el-col>
      <el-col :span="10">
        <div class="card">
          <h3 style="margin-bottom:12px;">👥 本周团队提交构成</h3>
          <v-chart :option="rankingOption" autoresize style="height:360px;" />
        </div>
      </el-col>
    </el-row>

    <el-row :gutter="16" class="mt-20">
      <el-col :span="12">
        <div class="card">
          <h3 style="margin-bottom:12px;">✍️ 我的个人统计（近{{ (personal.weekly_data||[]).length }}周）</h3>
          <el-descriptions size="small" :column="3" border style="margin-bottom:12px;">
            <el-descriptions-item label="总周数">{{ personal.total_weeks }}</el-descriptions-item>
            <el-descriptions-item label="已提交周数">{{ personal.submitted_weeks }}</el-descriptions-item>
            <el-descriptions-item label="提交率">{{ personal.submission_rate }}%</el-descriptions-item>
          </el-descriptions>
          <v-chart :option="personalOption" autoresize style="height:280px;" />
        </div>
      </el-col>
      <el-col :span="12">
        <div class="card">
          <h3 style="margin-bottom:12px;">☁️ 本周关键词词云 <el-tag type="info" size="small" style="margin-left:8px;">{{ wordCloud.length }}个词</el-tag></h3>
          <el-empty v-if="!wordCloud.length" description="暂无数据，请确保本周有已提交的周报" :image-size="80" />
          <div v-else style="height:340px;display:flex;flex-wrap:wrap;align-items:center;justify-content:center;gap:10px;padding:10px;overflow:hidden;">
            <span v-for="(w, i) in wordCloud.slice(0, 80)" :key="i"
              :style="{
                fontSize: `${12 + Math.min(28, w.count / Math.max(...wordCloud.slice(0,50).map(x=>x.count)) * 28)}px`,
                color: `hsl(${(i*41)%360}, 70%, ${40 + (i%5)*6}%)`,
                fontWeight: w.count > 5 ? 700 : 500
              }">
              {{ w.word }}
            </span>
          </div>
          <div style="margin-top:10px;padding-top:10px;border-top:1px dashed #ebeef5;">
            <div class="text-sm text-gray mb-10">Top 10 关键词：</div>
            <el-tag v-for="w in wordCloud.slice(0,10)" :key="w.word"
              :type="['primary','success','warning','danger','info'][wordCloud.indexOf(w) % 5]"
              style="margin:4px 8px 4px 0;">
              {{ w.word }} ({{ w.count }})
            </el-tag>
          </div>
        </div>
      </el-col>
    </el-row>

    <div class="card mt-20">
      <h3 style="margin-bottom:12px;">📋 团队填写速度排行榜</h3>
      <el-table :data="ranking" size="default" stripe>
        <el-table-column label="排名" width="80" align="center">
          <template #default="{ $index }">
            <el-tag v-if="$index === 0" type="warning" effect="dark">🥇 第1</el-tag>
            <el-tag v-else-if="$index === 1" type="info" effect="dark">🥈 第2</el-tag>
            <el-tag v-else-if="$index === 2" type="danger" effect="plain">🥉 第3</el-tag>
            <span v-else class="text-gray">第{{ $index + 1 }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="team_name" label="团队" width="140" />
        <el-table-column prop="leader_name" label="负责人" width="120" />
        <el-table-column label="提交进度" min-width="200">
          <template #default="{row}">
            <div class="flex-center" style="gap:10px;">
              <el-progress
                :percentage="row.submission_rate"
                :color="row.submission_rate >= 90 ? '#67c23a' : row.submission_rate >= 70 ? '#e6a23c' : '#f56c6c'"
                style="flex:1;"
              />
              <span class="text-sm text-bold">{{ row.submitted_count }}/{{ row.total_members }}</span>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="人均字数" width="120" align="center">
          <template #default="{row}">
            <b :style="{color: row.average_word_count > 500 ? '#67c23a' : '#909399'}">
              {{ row.average_word_count }}
            </b>
          </template>
        </el-table-column>
        <el-table-column prop="submission_rate" label="提交率" width="100" align="center">
          <template #default="{row}">
            <el-tag :type="row.submission_rate >= 90 ? 'success' : row.submission_rate >= 70 ? 'warning' : 'danger'" size="small">
              {{ row.submission_rate }}%
            </el-tag>
          </template>
        </el-table-column>
      </el-table>
    </div>

    <div class="card mt-20">
      <h3 style="margin-bottom:12px;">🔔 近期提醒日志 ({{ reminderLogs.length }}条)</h3>
      <el-empty v-if="!reminderLogs.length" description="暂无提醒日志" :image-size="80" />
      <el-table v-else :data="reminderLogs" size="small" max-height="300">
        <el-table-column label="时间" width="170">
          <template #default="{row}">{{ row.created_at?.slice(0,19).replace('T',' ') }}</template>
        </el-table-column>
        <el-table-column prop="user_name" label="成员" width="100" />
        <el-table-column label="类型" width="140">
          <template #default="{row}">
            <el-tag size="small">
              {{ { monday_first: '周一首次', wednesday_followup: '周三追加', friday_urgent: '周五紧急', deadline_2h: '截止前2h', manual: '手动触发' }[row.reminder_type] || row.reminder_type }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="渠道" width="100">
          <template #default="{row}">
            <el-tag type="info" size="small">{{ { wecom: '企业微信', feishu: '飞书', email: '邮件' }[row.channel] || row.channel }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="100">
          <template #default="{row}">
            <el-tag :type="row.status === 'success' ? 'success' : 'danger'" size="small">{{ row.status === 'success' ? '成功' : '失败' }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="error_message" label="错误信息" min-width="200" />
      </el-table>
    </div>
  </div>
</template>
