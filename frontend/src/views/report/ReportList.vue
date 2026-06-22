<script setup>
import { ref, onMounted, computed, reactive } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { reportApi, teamApi, userApi, exportApi } from '@/api'
import { useUserStore } from '@/store/user'
import { marked } from 'marked'
import dayjs from 'dayjs'

const userStore = useUserStore()
const router = useRouter()

const loading = ref(false)
const reports = ref([])
const teams = ref([])
const users = ref([])
const filters = reactive({
  week_key: '',
  team_id: null,
  submitter_id: null,
  status: ''
})

const pendingDialog = ref(false)
const pendingList = ref([])
const proxyDialog = ref(false)
const proxyForm = reactive({ user_id: null, content: {} })

const detailDialog = ref(false)
const detailReport = ref(null)

async function loadOptions() {
  teams.value = await teamApi.list()
  users.value = await userApi.list()
}

function generateWeekOptions() {
  const opts = []
  const today = dayjs()
  for (let i = 0; i < 8; i++) {
    const d = today.subtract(i, 'week')
    const monday = d.startOf('week').add(1, 'day')
    const friday = d.startOf('week').add(5, 'day')
    const weekKey = `${monday.year()}-W${String(monday.isoWeek()).padStart(2,'0')}`
    opts.push({
      value: weekKey,
      label: `${monday.format('MM月DD日')}-${friday.format('MM月DD日')} (第${monday.isoWeek()}周)`
    })
  }
  return opts
}
const weekOptions = generateWeekOptions()
filters.week_key = weekOptions[0]?.value

async function loadReports() {
  loading.value = true
  try {
    reports.value = await reportApi.list(filters)
  } finally {
    loading.value = false
  }
}

function renderMd(t) {
  try { return t ? marked.parse(t) : '' } catch { return t }
}

function getStatusTag(s) {
  const map = {
    draft: { txt: '草稿', cls: 'status-draft' },
    submitted: { txt: '已提交', cls: 'status-submitted' },
    pending: { txt: '待提交', cls: 'status-pending' }
  }
  return map[s] || { txt: s, cls: '' }
}

function getFirstFieldContent(content) {
  if (!content) return ''
  for (const k of ['week_achievement', 'achievement', '完成', '本周完成']) {
    if (content[k]) return String(content[k]).slice(0, 100) + (String(content[k]).length > 100 ? '...' : '')
  }
  const vals = Object.values(content).filter(v => typeof v === 'string' && v.length > 10)
  return vals.length ? vals[0].slice(0, 100) + (vals[0].length > 100 ? '...' : '') : ''
}

async function viewDetail(r) {
  detailReport.value = r
  detailDialog.value = true
}

async function handleRevoke(r) {
  await ElMessageBox.confirm(`确定撤回【${r.submitter_name}】的周报吗？`, '撤回确认', { type: 'warning' })
  try {
    await reportApi.revoke(r.id)
    ElMessage.success('已撤回')
    loadReports()
  } catch (e) {}
}

async function openPending() {
  const res = await reportApi.pendingUsers({ week_key: filters.week_key, team_id: filters.team_id })
  pendingList.value = res.pending_users || []
  pendingDialog.value = true
}

async function sendReminders() {
  const ids = pendingList.value.map(u => u.user_id)
  if (!ids.length) { ElMessage.warning('暂无待提交成员'); return }
  await ElMessageBox.confirm(`将对 ${ids.length} 人发送提醒，确定吗？`, '批量提醒', { type: 'warning' })
  try {
    const res = await exportApi.sendReminder({ user_ids: ids, reminder_type: 'manual' })
    ElMessage.success(`已发送 ${res.sent_count} 条提醒`)
    pendingDialog.value = false
  } catch (e) {}
}

function openProxy(user) {
  proxyForm.user_id = user.user_id
  proxyForm.content = {}
  proxyDialog.value = true
  pendingDialog.value = false
}

async function submitProxy() {
  try {
    await reportApi.proxySubmit(
      { content: proxyForm.content, status: 'submitted' },
      { proxy_user_id: proxyForm.user_id, week_key: filters.week_key }
    )
    ElMessage.success('代理提交成功')
    proxyDialog.value = false
    loadReports()
  } catch (e) {
    if (e?.response?.data?.detail) ElMessage.error(e.response.data.detail)
  }
}

onMounted(async () => {
  await loadOptions()
  loadReports()
})

watch(() => [filters.week_key, filters.team_id, filters.status], () => loadReports(), { deep: true })
import { watch } from 'vue'
</script>

<template>
  <div class="page-container">
    <div class="page-header">
      <div>
        <h2>📋 周报列表</h2>
        <div class="text-sm text-gray mt-10">共 {{ reports.length }} 条记录</div>
      </div>
      <div style="display:flex;gap:10px;">
        <el-button @click="router.push('/report/write')">✍️ 填写我的周报</el-button>
        <el-button v-if="userStore.isAdmin" type="warning" @click="openPending">⚠️ 查看待提交 ({{ pendingList.length }})</el-button>
      </div>
    </div>

    <div class="card mb-20">
      <el-form :inline="true" :model="filters">
        <el-form-item label="周次">
          <el-select v-model="filters.week_key" @change="loadReports" style="width:240px;">
            <el-option v-for="w in weekOptions" :key="w.value" :label="w.label" :value="w.value" />
          </el-select>
        </el-form-item>
        <el-form-item label="团队">
          <el-select v-model="filters.team_id" clearable placeholder="全部团队" style="width:160px;" @change="loadReports">
            <el-option v-for="t in teams" :key="t.id" :label="t.name" :value="t.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="成员">
          <el-select v-model="filters.submitter_id" clearable filterable placeholder="全部成员" style="width:160px;" @change="loadReports">
            <el-option v-for="u in users" :key="u.id" :label="u.full_name" :value="u.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="状态">
          <el-select v-model="filters.status" clearable placeholder="全部状态" style="width:120px;" @change="loadReports">
            <el-option label="草稿" value="draft" />
            <el-option label="已提交" value="submitted" />
          </el-select>
        </el-form-item>
        <el-form-item>
          <el-button @click="loadReports">🔄 刷新</el-button>
        </el-form-item>
      </el-form>
    </div>

    <div class="card">
      <el-table :data="reports" v-loading="loading" stripe size="default">
        <el-table-column label="成员" width="120">
          <template #default="{row}">
            <div class="flex-center">
              <el-avatar :size="32" style="background:#409eff;margin-right:8px;">{{ row.submitter_name?.charAt(0) }}</el-avatar>
              <div>
                <div style="font-weight:500;">{{ row.submitter_name }}</div>
                <div class="text-sm text-gray">{{ row.week_key }}</div>
              </div>
            </div>
          </template>
        </el-table-column>
        <el-table-column prop="template_name" label="模板" width="140" />
        <el-table-column label="内容摘要" min-width="300">
          <template #default="{row}">
            <div style="line-height:1.6;color:#606266;">{{ getFirstFieldContent(row.content) || '（无内容）' }}</div>
          </template>
        </el-table-column>
        <el-table-column label="字数" width="90" align="center">
          <template #default="{row}">
            <b :style="{color: row.word_count>500?'#67c23a':'#909399'}">{{ row.word_count }}</b>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="110" align="center">
          <template #default="{row}">
            <el-tag :class="'tag-badge ' + getStatusTag(row.status).cls">
              {{ getStatusTag(row.status).txt }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="提交时间" width="170">
          <template #default="{row}">
            <span v-if="row.submitted_at" class="text-sm">{{ row.submitted_at.replace('T',' ').slice(0,16) }}</span>
            <span v-else class="text-gray text-sm">—</span>
          </template>
        </el-table-column>
        <el-table-column label="代理" width="100" align="center">
          <template #default="{row}">
            <el-tag v-if="row.proxy_submitter_name" size="small" type="warning">
              {{ row.proxy_submitter_name }}代
            </el-tag>
            <span v-else class="text-gray text-sm">本人</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="200" fixed="right">
          <template #default="{row}">
            <el-button size="small" @click="viewDetail(row)">查看</el-button>
            <el-button v-if="userStore.isAdmin && row.status === 'submitted'" size="small" type="warning" @click="handleRevoke(row)">撤回</el-button>
          </template>
        </el-table-column>
      </el-table>
    </div>

    <el-dialog v-model="detailDialog" width="820px" title="周报详情">
      <div v-if="detailReport">
        <div style="display:flex;justify-content:space-between;padding-bottom:12px;border-bottom:1px solid #ebeef5;margin-bottom:16px;">
          <div>
            <h3 style="font-size:16px;">{{ detailReport.submitter_name }} 的周报</h3>
            <div class="text-sm text-gray mt-10">
              {{ detailReport.week_key }} · {{ detailReport.template_name || '标准模板' }} · {{ detailReport.word_count }}字
              <span v-if="detailReport.proxy_submitter_name"> · 由 <b>{{ detailReport.proxy_submitter_name }}</b> 代理提交</span>
            </div>
          </div>
          <el-tag :class="'tag-badge ' + getStatusTag(detailReport.status).cls">{{ getStatusTag(detailReport.status).txt }}</el-tag>
        </div>
        <div class="markdown-preview">
          <div v-for="(val, key) in (detailReport.content || {})" :key="key">
            <h4 style="color:#374151;margin:14px 0 8px;">🔹 {{ key }}</h4>
            <div v-if="typeof val === 'string'" v-html="renderMd(val)"></div>
            <div v-else-if="Array.isArray(val)">
              <el-tag v-for="v in val" :key="v" style="margin:4px 8px 4px 0;">{{ v }}</el-tag>
            </div>
            <div v-else>{{ String(val) }}</div>
          </div>
        </div>
      </div>
    </el-dialog>

    <el-dialog v-model="pendingDialog" width="680px" title="⚠️ 待提交成员列表">
      <el-empty v-if="!pendingList.length" description="本周全部提交完成 🎉" />
      <div v-else>
        <div class="mb-10 text-gray">共 {{ pendingList.length }} 人未提交</div>
        <el-table :data="pendingList" size="small">
          <el-table-column prop="user_name" label="姓名" width="100" />
          <el-table-column prop="team_name" label="团队" width="140" />
          <el-table-column prop="email" label="邮箱" />
          <el-table-column label="操作" width="160">
            <template #default="{row}">
              <el-button size="small" type="primary" @click="openProxy(row)">代理填写</el-button>
            </template>
          </el-table-column>
        </el-table>
        <div style="margin-top:16px;display:flex;justify-content:flex-end;gap:10px;">
          <el-button @click="pendingDialog = false">关闭</el-button>
          <el-button v-if="userStore.isAdmin" type="warning" @click="sendReminders">📢 批量发送提醒</el-button>
        </div>
      </div>
    </el-dialog>

    <el-dialog v-model="proxyDialog" width="600px" title="✍️ 代理填写周报">
      <el-form label-width="100px">
        <el-form-item label="为成员">
          <el-select v-model="proxyForm.user_id" style="width:100%;">
            <el-option v-for="u in pendingList" :key="u.user_id" :label="u.user_name + ' (' + u.team_name + ')'" :value="u.user_id" />
          </el-select>
        </el-form-item>
        <el-form-item label="本周完成 *">
          <el-input type="textarea" v-model="proxyForm.content.week_achievement" :rows="6" placeholder="请填写本周主要完成工作" />
        </el-form-item>
        <el-form-item label="下周计划 *">
          <el-input type="textarea" v-model="proxyForm.content.next_plan" :rows="4" placeholder="请填写下周工作计划" />
        </el-form-item>
        <el-form-item label="风险与阻塞">
          <el-input type="textarea" v-model="proxyForm.content.risk_block" :rows="3" placeholder="如有请填写，无则填'无'" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="proxyDialog = false">取消</el-button>
        <el-button type="primary" @click="submitProxy">提交</el-button>
      </template>
    </el-dialog>
  </div>
</template>
