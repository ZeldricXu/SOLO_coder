<script setup>
import { ref, onMounted, reactive } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { summaryApi, exportApi, statsApi } from '@/api'
import dayjs from 'dayjs'

const loading = ref(false)
const summaries = ref([])
const pendingUsers = ref([])
const schedulerJobs = ref([])

const weekOptions = ref([])
function generateWeeks() {
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
weekOptions.value = generateWeeks()

const distributeForm = reactive({
  week_key: weekOptions.value[0]?.value,
  format: 'pdf',
  email_to: '',
  push_wecom: true,
  push_feishu: false,
  push_confluence: false,
  push_yuque: false,
  push_notion: false
})
const distributeResult = ref([])

async function loadAll() {
  loading.value = true
  try {
    const [s, p] = await Promise.all([
      summaryApi.list(),
      statsApi.reminderLogs({ limit: 10 }).catch(() => [])
    ])
    summaries.value = s
    pendingUsers.value = p
    try {
      const r = await fetch('/api/health').then(r => r.json())
      schedulerJobs.value = r.scheduler_jobs || []
    } catch { /* ignore */ }
  } finally {
    loading.value = false
  }
}

async function triggerSummary() {
  await ElMessageBox.confirm('确定重新生成本周汇总吗？', '生成确认', { type: 'warning' })
  loading.value = true
  try {
    await summaryApi.generate({ week_key: distributeForm.week_key, force: true })
    ElMessage.success('已重新生成')
    loadAll()
  } finally {
    loading.value = false
  }
}

async function doDistribute() {
  const emails = distributeForm.email_to.split(/[,;，；\s]+/).filter(Boolean)
  if (!emails.length && !distributeForm.push_wecom && !distributeForm.push_feishu &&
      !distributeForm.push_confluence && !distributeForm.push_yuque && !distributeForm.push_notion) {
    return ElMessage.warning('请选择至少一种分发方式')
  }
  loading.value = true
  try {
    const res = await exportApi.distribute({
      week_key: distributeForm.week_key,
      format: distributeForm.format,
      email_to: emails,
      push_wecom: distributeForm.push_wecom,
      push_feishu: distributeForm.push_feishu,
      push_confluence: distributeForm.push_confluence,
      push_yuque: distributeForm.push_yuque,
      push_notion: distributeForm.push_notion
    })
    distributeResult.value = res.actions || []
    ElMessage.success('分发操作完成')
  } catch (e) {
  } finally {
    loading.value = false
  }
}

async function triggerReminder() {
  await ElMessageBox.confirm('将对本周所有未提交成员发送提醒（企微、飞书、邮件），确定吗？', '批量提醒', { type: 'warning' })
  loading.value = true
  try {
    const res = await exportApi.sendReminder({ reminder_type: 'manual', week_key: distributeForm.week_key })
    ElMessage.success(`已发送 ${res.sent_count || 0} 条提醒`)
  } finally {
    loading.value = false
  }
}

async function runJob(jobName) {
  const nameMap = {
    '周一9点提醒': 'monday_reminder',
    '周三10点追加': 'wednesday_reminder',
    '周五10点紧急': 'friday_reminder',
    '周五18点汇总': 'generate_summary'
  }
  const jn = nameMap[jobName] || 'generate_summary'
  await ElMessageBox.confirm(`立即执行【${jobName}】任务？`, '手动触发', { type: 'warning' })
  try {
    await fetch(`/api/scheduler/trigger/${jn}`, { method: 'POST' }).then(r => r.json())
    ElMessage.success('任务已触发')
    loadAll()
  } catch (e) {}
}

function downloadSummary(s, format) {
  exportApi.download(s.id, format).then(blob => {
    const url = window.URL.createObjectURL(new Blob([blob]))
    const a = document.createElement('a')
    a.href = url
    a.download = `weekly_summary_${s.week_key}.${format}`
    a.click()
    window.URL.revokeObjectURL(url)
  })
}

onMounted(loadAll)
</script>

<template>
  <div class="page-container">
    <div class="page-header">
      <div>
        <h2>📤 导出与分发</h2>
        <div class="text-sm text-gray mt-10">汇总报告导出、多渠道分发、定时任务管理</div>
      </div>
      <div style="display:flex;gap:10px;">
        <el-button @click="loadAll">🔄 刷新</el-button>
      </div>
    </div>

    <el-row :gutter="16">
      <el-col :span="14">
        <div class="card">
          <h3 style="margin-bottom:14px;">📦 分发汇总报告</h3>
          <el-form label-width="110px">
            <el-form-item label="选择周次">
              <el-select v-model="distributeForm.week_key" style="width:260px;" @change="loadAll">
                <el-option v-for="w in weekOptions" :key="w.value" :label="w.label" :value="w.value" />
              </el-select>
            </el-form-item>
            <el-form-item label="附件格式">
              <el-radio-group v-model="distributeForm.format">
                <el-radio value="pdf">PDF</el-radio>
                <el-radio value="markdown">Markdown</el-radio>
              </el-radio-group>
            </el-form-item>
            <el-divider content-position="left">🚀 分发渠道</el-divider>
            <el-form-item label="邮件发送">
              <el-input v-model="distributeForm.email_to" type="textarea" :rows="2"
                placeholder="留空则发送到默认收件人，多个邮箱用逗号/分号分隔" />
            </el-form-item>
            <el-form-item label="渠道开关">
              <div style="display:flex;flex-direction:column;gap:8px;">
                <el-checkbox v-model="distributeForm.push_wecom" border>💼 推送到企业微信群（各团队配置的webhook）</el-checkbox>
                <el-checkbox v-model="distributeForm.push_feishu" border>📘 推送到飞书群（各团队配置的webhook）</el-checkbox>
                <el-checkbox v-model="distributeForm.push_confluence" border>📖 推送到 Confluence</el-checkbox>
                <el-checkbox v-model="distributeForm.push_yuque" border>🐦 推送到语雀</el-checkbox>
                <el-checkbox v-model="distributeForm.push_notion" border>📝 推送到 Notion</el-checkbox>
              </div>
            </el-form-item>
            <el-alert v-if="!distributeForm.push_wecom && !distributeForm.push_feishu &&
              !distributeForm.push_confluence && !distributeForm.push_yuque && !distributeForm.push_notion &&
              !distributeForm.email_to"
              type="info" show-icon :closable="false">
              提示：请在 .env 中配置相应渠道的凭据后即可使用推送功能
            </el-alert>
          </el-form>
          <div style="display:flex;gap:10px;margin-top:16px;">
            <el-button type="primary" size="large" :loading="loading" @click="doDistribute">🚀 立即分发</el-button>
            <el-button type="success" size="large" @click="triggerSummary">🔄 重新生成汇总</el-button>
            <el-button type="warning" size="large" @click="triggerReminder">📢 发送批量提醒</el-button>
          </div>
          <div v-if="distributeResult.length" class="card" style="margin-top:16px;background:#f5f7fa;">
            <h5 style="margin-bottom:10px;">分发执行结果：</h5>
            <el-table :data="distributeResult" size="small">
              <el-table-column label="渠道" width="130">
                <template #default="{row}">
                  <el-tag type="primary" size="small">{{ row.type }}</el-tag>
                </template>
              </el-table-column>
              <el-table-column label="目标">
                <template #default="{row}">
                  <span class="text-sm">{{ row.team || row.recipients?.join(',') || '-' }}</span>
                </template>
              </el-table-column>
              <el-table-column label="状态" width="100">
                <template #default="{row}">
                  <el-tag :type="row.status==='success' ? 'success' : 'danger'" size="small">
                    {{ row.status === 'success' ? '成功' : '失败' }}
                  </el-tag>
                </template>
              </el-table-column>
              <el-table-column prop="error" label="详情" />
            </el-table>
          </div>
        </div>
      </el-col>

      <el-col :span="10">
        <div class="card">
          <h3 style="margin-bottom:14px;">⏰ 定时任务调度</h3>
          <el-alert type="success" show-icon :closable="false" style="margin-bottom:14px;">
            APScheduler 已启用，定时任务将按以下配置自动执行
          </el-alert>
          <el-table :data="[
            { name: '周一9点提醒', desc: '每周一上午推送首次填写提醒', next: schedulerJobs.find(j=>j.id==='monday_first_reminder')?.next_run_time },
            { name: '周三10点追加', desc: '周三补充提醒未填人员', next: schedulerJobs.find(j=>j.id==='wednesday_followup')?.next_run_time },
            { name: '周五10点紧急', desc: '周五紧急提醒', next: schedulerJobs.find(j=>j.id==='friday_urgent')?.next_run_time },
            { name: '周五18点汇总', desc: '自动生成+汇总+推送分发', next: schedulerJobs.find(j=>j.id==='weekly_summary_generation')?.next_run_time }
          ]" size="default">
            <el-table-column prop="name" label="任务名" width="130" />
            <el-table-column prop="desc" label="描述" />
            <el-table-column label="下次执行" width="160">
              <template #default="{row}">
                <span class="text-sm text-gray">{{ row.next?.slice(0,19).replace('T',' ') || '—' }}</span>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="90">
              <template #default="{row}">
                <el-button size="small" type="primary" link @click="runJob(row.name)">立即执行</el-button>
              </template>
            </el-table-column>
          </el-table>
        </div>

        <div class="card mt-20">
          <h3 style="margin-bottom:14px;">📚 历史汇总记录</h3>
          <el-table :data="summaries.slice(0,6)" size="small">
            <el-table-column label="周次" width="120">
              <template #default="{row}">
                <el-tag size="small" type="info">{{ row.week_key }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="提交率">
              <template #default="{row}">
                <el-progress :percentage="row.content?.overall_stats?.submission_rate || 0" :stroke-width="12" />
              </template>
            </el-table-column>
            <el-table-column label="状态" width="90">
              <template #default="{row}">
                <el-tag v-if="row.status==='distributed'" type="success" size="small">已分发</el-tag>
                <el-tag v-else type="info" size="small">已生成</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="下载" width="140">
              <template #default="{row}">
                <el-button-group>
                  <el-button size="small" @click="downloadSummary(row,'pdf')">PDF</el-button>
                  <el-button size="small" @click="downloadSummary(row,'markdown')">MD</el-button>
                  <el-button size="small" @click="downloadSummary(row,'json')">JSON</el-button>
                </el-button-group>
              </template>
            </el-table-column>
          </el-table>
        </div>
      </el-col>
    </el-row>
  </div>
</template>
