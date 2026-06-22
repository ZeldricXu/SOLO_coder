<script setup>
import { ref, onMounted, reactive } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { teamApi, userApi, templateApi } from '@/api'

const loading = ref(false)
const teams = ref([])
const users = ref([])
const templates = ref([])

const dialog = ref(false)
const form = reactive({
  id: null, name: '', description: '', leader_id: null,
  deadline_day: 4, deadline_hour: 18, deadline_minute: 0,
  template_id: null
})

const notifyDialog = ref(false)
const notifyForm = reactive({
  team_id: null, team_name: '',
  wecom_webhook: '', feishu_webhook: '', notify_emails: '',
  notify_wecom_enabled: false, notify_feishu_enabled: false, notify_email_enabled: false
})

const dayOptions = [
  { value: 1, label: '周一' },
  { value: 2, label: '周二' },
  { value: 3, label: '周三' },
  { value: 4, label: '周四' },
  { value: 5, label: '周五' },
  { value: 6, label: '周六' },
  { value: 7, label: '周日' }
]

async function loadAll() {
  loading.value = true
  try {
    const [t, u, tp] = await Promise.all([teamApi.list(), userApi.list(), templateApi.list()])
    teams.value = t; users.value = u; templates.value = tp
  } finally {
    loading.value = false
  }
}

function openCreate() {
  Object.assign(form, {
    id: null, name: '', description: '', leader_id: null,
    deadline_day: 4, deadline_hour: 18, deadline_minute: 0,
    template_id: templates.value[0]?.id || null
  })
  dialog.value = true
}

function openEdit(t) {
  Object.assign(form, {
    id: t.id, name: t.name, description: t.description, leader_id: t.leader_id,
    deadline_day: t.deadline_day, deadline_hour: t.deadline_hour, deadline_minute: t.deadline_minute,
    template_id: t.template_id
  })
  dialog.value = true
}

async function save() {
  if (!form.name.trim()) return ElMessage.warning('请填写团队名称')
  try {
    if (form.id) {
      await teamApi.update(form.id, { ...form })
      ElMessage.success('已更新')
    } else {
      await teamApi.create({ ...form })
      ElMessage.success('创建成功')
    }
    dialog.value = false
    loadAll()
  } catch (e) {
    if (e?.response?.data?.detail) ElMessage.error(e.response.data.detail)
  }
}

async function handleDelete(t) {
  await ElMessageBox.confirm(`确定删除团队【${t.name}】吗？`, '删除确认', { type: 'error' })
  try {
    await teamApi.remove(t.id)
    ElMessage.success('已删除')
    loadAll()
  } catch (e) {
    if (e?.response?.data?.detail) ElMessage.warning(e.response.data.detail)
  }
}

async function openNotify(t) {
  try {
    const s = await teamApi.getNotifySetting(t.id)
    Object.assign(notifyForm, { team_id: t.id, team_name: t.name, ...s })
  } catch {
    Object.assign(notifyForm, {
      team_id: t.id, team_name: t.name,
      wecom_webhook: '', feishu_webhook: '', notify_emails: '',
      notify_wecom_enabled: false, notify_feishu_enabled: false, notify_email_enabled: false
    })
  }
  notifyDialog.value = true
}

async function saveNotify() {
  await teamApi.updateNotifySetting(notifyForm.team_id, notifyForm)
  ElMessage.success('通知设置已保存')
  notifyDialog.value = false
}

onMounted(loadAll)
</script>

<template>
  <div class="page-container">
    <div class="page-header">
      <div>
        <h2>👥 团队管理</h2>
        <div class="text-sm text-gray mt-10">管理团队结构、负责人、截止时间和通知配置</div>
      </div>
      <el-button type="primary" @click="openCreate">+ 新建团队</el-button>
    </div>

    <div class="card" v-loading="loading">
      <el-table :data="teams" stripe>
        <el-table-column label="团队名称" min-width="140">
          <template #default="{row}">
            <b>{{ row.name }}</b>
            <div class="text-sm text-gray mt-10" v-if="row.description">{{ row.description }}</div>
          </template>
        </el-table-column>
        <el-table-column label="负责人" width="120">
          <template #default="{row}">{{ row.leader_name || '—' }}</template>
        </el-table-column>
        <el-table-column label="成员数" width="90" align="center">
          <template #default="{row}"><el-tag type="info" size="small">{{ row.member_count }}</el-tag></template>
        </el-table-column>
        <el-table-column label="使用模板" width="150">
          <template #default="{row}">
            <el-tag v-if="row.template_name" size="small">{{ row.template_name }}</el-tag>
            <span v-else class="text-gray">—</span>
          </template>
        </el-table-column>
        <el-table-column label="截止时间" width="160">
          <template #default="{row}">
            每周{{ {1:'一',2:'二',3:'三',4:'四',5:'五',6:'六',7:'日'}[row.deadline_day] }}
            {{ String(row.deadline_hour).padStart(2,'0') }}:{{ String(row.deadline_minute).padStart(2,'0') }}
          </template>
        </el-table-column>
        <el-table-column label="操作" width="220" fixed="right">
          <template #default="{row}">
            <el-button size="small" @click="openEdit(row)">编辑</el-button>
            <el-button size="small" type="warning" @click="openNotify(row)">通知</el-button>
            <el-button size="small" type="danger" @click="handleDelete(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
    </div>

    <el-dialog v-model="dialog" :title="form.id ? '编辑团队' : '新建团队'" width="560px">
      <el-form label-width="90px">
        <el-form-item label="团队名称 *">
          <el-input v-model="form.name" />
        </el-form-item>
        <el-form-item label="团队描述">
          <el-input v-model="form.description" type="textarea" :rows="2" />
        </el-form-item>
        <el-form-item label="负责人">
          <el-select v-model="form.leader_id" filterable clearable style="width:100%;">
            <el-option v-for="u in users" :key="u.id" :label="u.full_name + ' (' + u.username + ')'" :value="u.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="使用模板">
          <el-select v-model="form.template_id" clearable style="width:100%;">
            <el-option v-for="t in templates.filter(x=>x.is_active)" :key="t.id" :label="t.name + (t.is_default ? '（默认）' : '')" :value="t.id" />
          </el-select>
        </el-form-item>
        <el-divider content-position="left">⏰ 周报截止时间</el-divider>
        <el-row :gutter="12">
          <el-col :span="8">
            <el-form-item label="截止日期">
              <el-select v-model="form.deadline_day" style="width:100%;">
                <el-option v-for="d in dayOptions" :key="d.value" :label="d.label" :value="d.value" />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :span="8">
            <el-form-item label="时">
              <el-select v-model="form.deadline_hour" style="width:100%;">
                <el-option v-for="h in Array.from({length:24}, (_,i)=>i)" :key="h" :label="`${String(h).padStart(2,'0')}点`" :value="h" />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :span="8">
            <el-form-item label="分">
              <el-select v-model="form.deadline_minute" style="width:100%;">
                <el-option v-for="m in [0,15,30,45]" :key="m" :label="`${String(m).padStart(2,'0')}分`" :value="m" />
              </el-select>
            </el-form-item>
          </el-col>
        </el-row>
      </el-form>
      <template #footer>
        <el-button @click="dialog = false">取消</el-button>
        <el-button type="primary" @click="save">保存</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="notifyDialog" :title="`🔔 通知配置 - ${notifyForm.team_name}`" width="540px">
      <el-alert type="warning" show-icon style="margin-bottom:14px;">
        配置团队级别的推送渠道，用于自动发送周报提醒和汇总报告
      </el-alert>
      <el-form label-width="110px">
        <el-form-item label="启用企微推送">
          <el-switch v-model="notifyForm.notify_wecom_enabled" />
        </el-form-item>
        <el-form-item label="企业微信Webhook">
          <el-input v-model="notifyForm.wecom_webhook" :disabled="!notifyForm.notify_wecom_enabled" placeholder="https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=..." />
        </el-form-item>
        <el-form-item label="启用飞书推送">
          <el-switch v-model="notifyForm.notify_feishu_enabled" />
        </el-form-item>
        <el-form-item label="飞书Webhook">
          <el-input v-model="notifyForm.feishu_webhook" :disabled="!notifyForm.notify_feishu_enabled" placeholder="https://open.feishu.cn/open-apis/bot/v2/hook/..." />
        </el-form-item>
        <el-form-item label="启用邮件推送">
          <el-switch v-model="notifyForm.notify_email_enabled" />
        </el-form-item>
        <el-form-item label="收件邮箱">
          <el-input v-model="notifyForm.notify_emails" type="textarea" :rows="2"
            :disabled="!notifyForm.notify_email_enabled"
            placeholder="多个邮箱用逗号分隔，如：a@xx.com,b@xx.com" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="notifyDialog = false">关闭</el-button>
        <el-button type="primary" @click="saveNotify">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>
