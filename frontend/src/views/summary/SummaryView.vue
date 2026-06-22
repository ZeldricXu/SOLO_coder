<script setup>
import { ref, onMounted, reactive } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { summaryApi, exportApi } from '@/api'
import { useUserStore } from '@/store/user'
import { marked } from 'marked'
import dayjs from 'dayjs'

const userStore = useUserStore()
const loading = ref(false)
const currentWeek = ref('')
const summary = ref(null)
const history = ref([])

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

async function loadSummary() {
  loading.value = true
  try {
    summary.value = await summaryApi.current(currentWeek.value)
  } catch (e) {
    summary.value = null
  } finally {
    loading.value = false
  }
}

async function regenerate() {
  await ElMessageBox.confirm('确定重新生成本周汇总吗？将覆盖之前的汇总。', '重新生成', { type: 'warning' })
  loading.value = true
  try {
    summary.value = await summaryApi.generate({ week_key: currentWeek.value, force: true })
    ElMessage.success('已重新生成')
  } finally {
    loading.value = false
  }
}

function renderMd(t) {
  try { return t ? marked.parse(t) : '<span class="text-gray">（空）</span>' } catch { return t }
}

async function handleDownload(format) {
  if (!summary.value) return
  try {
    const blob = await exportApi.download(summary.value.id, format)
    const url = window.URL.createObjectURL(new Blob([blob]))
    const a = document.createElement('a')
    a.href = url
    a.download = `weekly_summary_${summary.value.week_key}.${format === 'json' ? 'json' : format === 'markdown' ? 'md' : 'pdf'}`
    a.click()
    window.URL.revokeObjectURL(url)
  } catch (e) {}
}

const distributeForm = reactive({
  email_to: '',
  push_wecom: false,
  push_feishu: false,
  push_confluence: false,
  push_yuque: false,
  push_notion: false,
  format: 'pdf'
})
const distributeDialog = ref(false)
const distributeResult = ref([])

async function openDistribute() {
  distributeForm.email_to = ''
  distributeForm.push_wecom = false
  distributeForm.push_feishu = false
  distributeResult.value = []
  distributeDialog.value = true
}

async function doDistribute() {
  const emails = distributeForm.email_to.split(/[,;，；\s]+/).filter(Boolean)
  loading.value = true
  try {
    const res = await exportApi.distribute({
      week_key: currentWeek.value,
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

onMounted(loadSummary)
</script>

<template>
  <div class="page-container">
    <div class="page-header">
      <div>
        <h2>📑 周报汇总</h2>
        <div class="text-sm text-gray mt-10">
          {{ summary?.content?.week_display || summary?.week_key || '加载中...' }}
        </div>
      </div>
      <div style="display:flex;gap:10px;align-items:center;">
        <el-select v-model="currentWeek" @change="loadSummary" style="width:220px;">
          <el-option v-for="w in weekOptions" :key="w.value" :label="w.label" :value="w.value" />
        </el-select>
        <el-dropdown>
          <el-button>📥 下载 <el-icon><ArrowDown /></el-icon></el-button>
          <template #dropdown>
            <el-dropdown-menu>
              <el-dropdown-item @click="handleDownload('pdf')">PDF 格式</el-dropdown-item>
              <el-dropdown-item @click="handleDownload('markdown')">Markdown 格式</el-dropdown-item>
              <el-dropdown-item @click="handleDownload('json')">JSON 数据</el-dropdown-item>
            </el-dropdown-menu>
          </template>
        </el-dropdown>
        <el-button v-if="userStore.isAdmin" @click="openDistribute">📤 分发</el-button>
        <el-button v-if="userStore.isAdmin" type="primary" @click="regenerate">🔄 重新生成</el-button>
      </div>
    </div>

    <div v-loading="loading">
      <el-empty v-if="!summary" description="该周暂无汇总，请先确保有已提交的周报后点击重新生成" />
      <div v-else>
        <div class="stat-grid">
          <div class="stat-card">
            <div class="stat-num">{{ summary.content.overall_stats?.total_users || 0 }}</div>
            <div class="stat-label">总人数</div>
          </div>
          <div class="stat-card success">
            <div class="stat-num">{{ summary.content.overall_stats?.submitted_count || 0 }}</div>
            <div class="stat-label">已提交</div>
          </div>
          <div class="stat-card danger">
            <div class="stat-num">{{ summary.content.overall_stats?.pending_count || 0 }}</div>
            <div class="stat-label">未提交</div>
          </div>
          <div class="stat-card warning">
            <div class="stat-num">{{ summary.content.overall_stats?.submission_rate || 0 }}<span style="font-size:18px;">%</span></div>
            <div class="stat-label">提交率</div>
          </div>
          <div class="stat-card danger">
            <div class="stat-num">{{ summary.content.risks?.total_count || 0 }}</div>
            <div class="stat-label">风险项</div>
          </div>
          <div class="stat-card purple">
            <div class="stat-num">{{ summary.content.deviation_count || summary.deviation_items?.length || 0 }}</div>
            <div class="stat-label">计划偏离</div>
          </div>
        </div>

        <el-collapse v-model="['risks']" accordion>
          <el-collapse-item v-if="summary.content.risks?.items?.length" name="risks">
            <template #title>
              <div style="display:flex;align-items:center;gap:10px;font-weight:600;">
                <span style="color:#f56c6c;">⚠️</span>
                <span>本周风险与阻塞项（标红）</span>
                <el-tag type="danger" size="small">{{ summary.content.risks.total_count }}</el-tag>
              </div>
            </template>
            <div style="padding:8px 0;">
              <div v-for="(r, idx) in summary.content.risks.items" :key="idx" class="risk-highlight">
                <div style="font-weight:600;margin-bottom:6px;">
                  <el-tag type="danger" size="small" style="margin-right:8px;">{{ idx + 1 }}</el-tag>
                  <el-tag size="small" style="margin-right:8px;">{{ r.team_name }}</el-tag>
                  <b>{{ r.user_name }}</b>
                </div>
                <div class="markdown-preview" v-html="renderMd(r.content)"></div>
              </div>
            </div>
          </el-collapse-item>

          <el-collapse-item v-if="summary.deviation_items?.length" name="deviation">
            <template #title>
              <div style="display:flex;align-items:center;gap:10px;font-weight:600;">
                <span>❌</span>
                <span>计划偏离对比（上周计划 vs 本周完成）</span>
                <el-tag type="warning" size="small">{{ summary.deviation_items.length }}项</el-tag>
              </div>
            </template>
            <el-table :data="summary.deviation_items" size="small">
              <el-table-column label="成员" prop="user_name" width="100" />
              <el-table-column label="上周计划项" min-width="320">
                <template #default="{row}">
                  <div style="color:#92400e;background:#fffbeb;padding:6px 10px;border-radius:4px;">{{ row.planned_item }}</div>
                </template>
              </el-table-column>
              <el-table-column label="实际状态" width="100">
                <template #default="{row}">
                  <el-tag :type="row.actual_status === '未完成' ? 'danger' : 'warning'" size="small">{{ row.actual_status }}</el-tag>
                </template>
              </el-table-column>
              <el-table-column label="偏离级别" width="100">
                <template #default="{row}">
                  <el-tag :type="row.deviation_level === 'major' ? 'danger' : 'warning'" size="small">{{ row.deviation_level === 'major' ? '重大' : '一般' }}</el-tag>
                </template>
              </el-table-column>
              <el-table-column label="备注" min-width="200" prop="note" />
            </el-table>
          </el-collapse-item>

          <el-collapse-item name="byteam">
            <template #title>
              <div style="display:flex;align-items:center;gap:10px;font-weight:600;">
                <span>👥</span>
                <span>按团队维度明细</span>
              </div>
            </template>
            <div v-for="t in summary.content.by_team || []" :key="t.team_id" class="card mb-20" style="box-shadow:none;border:1px solid #ebeef5;">
              <div class="flex-between mb-10">
                <h4 style="font-size:15px;">
                  🏢 {{ t.team_name }}
                  <el-tag type="success" style="margin-left:10px;">
                    {{ t.submitted_count }}/{{ t.total_members }} ({{ t.total_members ? Math.round(t.submitted_count/t.total_members*100) : 0 }}%)
                  </el-tag>
                </h4>
                <div class="text-sm text-gray">
                  {{ t.risks.length }} 项风险
                </div>
              </div>
              <table style="width:100%;border-collapse:collapse;font-size:13px;">
                <thead>
                  <tr style="background:#f5f7fa;">
                    <th style="padding:8px 12px;text-align:left;border:1px solid #ebeef5;">成员</th>
                    <th style="padding:8px 12px;text-align:left;border:1px solid #ebeef5;">提交时间</th>
                    <th style="padding:8px 12px;text-align:left;border:1px solid #ebeef5;">字数</th>
                    <th style="padding:8px 12px;text-align:left;border:1px solid #ebeef5;">内容摘要</th>
                  </tr>
                </thead>
                <tbody>
                  <tr v-for="r in t.reports" :key="r.report_id" style="border-bottom:1px solid #f0f2f5;">
                    <td style="padding:8px 12px;border:1px solid #ebeef5;font-weight:500;">
                      <el-avatar :size="22" style="background:#409eff;margin-right:6px;">{{ r.user_name?.charAt(0) }}</el-avatar>
                      {{ r.user_name }}
                    </td>
                    <td style="padding:8px 12px;border:1px solid #ebeef5;" class="text-sm text-gray">
                      {{ r.submitted_at?.slice(0,16).replace('T',' ') || '—' }}
                    </td>
                    <td style="padding:8px 12px;border:1px solid #ebeef5;">{{ r.word_count }}</td>
                    <td style="padding:8px 12px;border:1px solid #ebeef5;max-width:400px;">
                      <div style="max-height:60px;overflow:auto;color:#606266;">
                        <template v-for="(v, k) in (r.content_summary || {})" :key="k">
                          <div v-if="typeof v === 'string' && v.length < 200"><b>{{ k }}</b>：{{ v.slice(0,100) }}{{ v.length > 100 ? '...' : '' }}</div>
                        </template>
                      </div>
                    </td>
                  </tr>
                  <tr v-if="!t.reports.length">
                    <td colspan="4" style="padding:20px;text-align:center;color:#909399;border:1px solid #ebeef5;">本周暂无提交</td>
                  </tr>
                </tbody>
              </table>
            </div>
          </el-collapse-item>
        </el-collapse>
      </div>
    </div>

    <el-dialog v-model="distributeDialog" width="540px" title="📤 分发汇总报告">
      <el-form label-width="110px">
        <el-form-item label="发送到邮箱">
          <el-input v-model="distributeForm.email_to" type="textarea" :rows="2" placeholder="多个邮箱用逗号或分号分隔，留空则使用默认收件人" />
        </el-form-item>
        <el-form-item label="推送渠道">
          <el-checkbox-group v-model="distributeForm" style="display:flex;flex-direction:column;gap:8px;">
            <el-checkbox label-value label="push_wecom">企业微信群</el-checkbox>
            <el-checkbox label-value label="push_feishu">飞书群</el-checkbox>
            <el-checkbox label-value label="push_confluence">Confluence</el-checkbox>
            <el-checkbox label-value label="push_yuque">语雀</el-checkbox>
            <el-checkbox label-value label="push_notion">Notion</el-checkbox>
          </el-checkbox-group>
        </el-form-item>
        <el-form-item label="附件格式">
          <el-select v-model="distributeForm.format" style="width:160px;">
            <el-option label="PDF" value="pdf" />
            <el-option label="Markdown" value="markdown" />
          </el-select>
        </el-form-item>
      </el-form>
      <div v-if="distributeResult.length" class="card" style="margin-top:10px;">
        <h5 style="margin-bottom:8px;">分发结果：</h5>
        <div v-for="(r, i) in distributeResult" :key="i" style="padding:4px 0;font-size:13px;">
          <el-tag :type="r.status === 'success' ? 'success' : 'danger'" size="small" style="margin-right:8px;">{{ r.status }}</el-tag>
          [{{ r.type }}] {{ r.team || r.recipients?.join(',') || '' }}
          <span v-if="r.error" style="color:#f56c6c;margin-left:6px;">{{ r.error }}</span>
        </div>
      </div>
      <template #footer>
        <el-button @click="distributeDialog = false">取消</el-button>
        <el-button type="primary" :loading="loading" @click="doDistribute">立即分发</el-button>
      </template>
    </el-dialog>
  </div>
</template>
