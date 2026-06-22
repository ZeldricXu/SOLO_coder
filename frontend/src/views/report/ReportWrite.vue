<script setup>
import { ref, onMounted, computed, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { reportApi, templateApi } from '@/api'
import { marked } from 'marked'

const router = useRouter()
const loading = ref(false)
const report = ref(null)
const template = ref(null)
const formData = ref({})
const previewMode = ref(false)
const autoSaveTimer = ref(null)
const lastSaved = ref('')

const weekDisplay = computed(() => {
  if (!report.value) return ''
  const s = report.value.week_start, e = report.value.week_end
  return `${s} 至 ${e}`
})

const wordCount = computed(() => {
  let total = 0
  Object.values(formData.value).forEach(v => {
    if (typeof v === 'string') total += v.replace(/[#*`>\-\s]+/g, '').length
    else if (Array.isArray(v)) total += v.join('').length
  })
  return total
})

function renderMarkdown(text) {
  if (!text) return ''
  try { return marked.parse(text || '') }
  catch { return text }
}

function getFieldValue(field) {
  const val = formData.value[field.field_key]
  if (field.field_type === 'multiselect') return Array.isArray(val) ? val : []
  if (field.field_type === 'select') return val || ''
  return val || ''
}

function setFieldValue(field, val) {
  formData.value[field.field_key] = val
  triggerAutoSave()
}

function triggerAutoSave() {
  if (autoSaveTimer.value) clearTimeout(autoSaveTimer.value)
  autoSaveTimer.value = setTimeout(() => saveDraft(), 1500)
}

async function saveDraft() {
  if (!report.value) return
  try {
    const res = await reportApi.update(report.value.id, {
      content: { ...formData.value },
      status: 'draft'
    })
    report.value = res
    lastSaved.value = new Date().toLocaleTimeString()
  } catch (e) { /* ignore */ }
}

async function loadCurrentReport() {
  loading.value = true
  try {
    const r = await reportApi.myCurrent()
    report.value = r
    formData.value = { ...(r.content || {}) }
    if (r.template_id) {
      try {
        template.value = await templateApi.get(r.template_id)
      } catch (e) { template.value = await templateApi.getDefault() }
    } else {
      template.value = await templateApi.getDefault()
    }
  } finally {
    loading.value = false
  }
}

async function handleSubmit() {
  const fields = template.value?.fields || []
  const missing = fields.filter(f => f.is_required && (
    !formData.value[f.field_key] ||
    (typeof formData.value[f.field_key] === 'string' && !formData.value[f.field_key].trim())
  ))
  if (missing.length) {
    ElMessage.warning(`请先填写必填字段：${missing.map(m => m.field_name).join('、')}`)
    return
  }

  await ElMessageBox.confirm(
    `确认提交本周周报吗？提交后如需修改请联系TL或管理员撤回。\n当前字数：${wordCount.value}字`,
    '提交确认',
    { type: 'warning', confirmButtonText: '确认提交', cancelButtonText: '再看看' }
  )

  try {
    const res = await reportApi.update(report.value.id, {
      content: { ...formData.value },
      status: 'submitted'
    })
    report.value = res
    ElMessage.success('🎉 周报提交成功！')
  } catch (e) {
    if (e?.response?.data?.detail) ElMessage.error(e.response.data.detail)
  }
}

async function handleRevoke() {
  await ElMessageBox.confirm('确定撤回本周报吗？撤回后将回到草稿状态。', '撤回确认', { type: 'warning' })
  try {
    await reportApi.revoke(report.value.id)
    report.value.status = 'draft'
    report.value.submitted_at = null
    ElMessage.success('已撤回')
  } catch (e) {
    if (e?.response?.data?.detail) ElMessage.error(e.response.data.detail)
  }
}

onMounted(loadCurrentReport)
</script>

<template>
  <div class="page-container">
    <div class="page-header">
      <div>
        <h2>✍️ 填写周报 <el-tag size="small" style="margin-left:8px;">{{ weekDisplay }}</el-tag></h2>
        <div class="text-sm text-gray mt-10">
          模板：<b>{{ template?.name || '标准模板' }}</b>
          <span v-if="lastSaved" style="margin-left:16px;">💾 上次自动保存：{{ lastSaved }}</span>
        </div>
      </div>
      <div style="display:flex;gap:10px;align-items:center;">
        <div style="font-size:14px;color:#606266;">
          📝 字数：<b :style="{color: wordCount>500?'#67c23a':'#e6a23c'}">{{ wordCount }}</b>
        </div>
        <el-tag :class="report?.status === 'submitted' ? 'tag-badge status-submitted' : 'tag-badge status-draft'">
          {{ report?.status === 'submitted' ? '✅ 已提交' : '📋 草稿' }}
        </el-tag>
        <el-button :type="previewMode ? 'primary' : ''" @click="previewMode = !previewMode">
          {{ previewMode ? '📝 编辑' : '👁 预览' }}
        </el-button>
        <el-button @click="saveDraft" :disabled="report?.status === 'submitted'">💾 保存草稿</el-button>
        <el-button v-if="report?.status === 'submitted'" type="warning" @click="handleRevoke">↩️ 撤回</el-button>
        <el-button v-else type="primary" @click="handleSubmit">🚀 提交</el-button>
      </div>
    </div>

    <el-row :gutter="20">
      <el-col :span="previewMode ? 12 : 24">
        <div class="card" v-loading="loading">
          <el-empty v-if="!template" description="加载模板中..." />
          <div v-else>
            <template v-for="field in (template.fields || []).sort((a,b)=>a.sort_order-b.sort_order)" :key="field.field_key">
              <div style="margin-bottom:24px;">
                <div class="flex-between mb-10">
                  <label style="font-size:14px;font-weight:600;color:#1f2937;">
                    <span v-if="field.is_required" class="text-red">*</span>
                    {{ field.field_name }}
                    <span v-if="field.is_risk_field" style="font-size:12px;color:#f56c6c;margin-left:8px;">(风险字段)</span>
                    <span v-if="field.is_plan_field" style="font-size:12px;color:#409eff;margin-left:8px;">(下周计划)</span>
                    <span v-if="field.is_achievement_field" style="font-size:12px;color:#67c23a;margin-left:8px;">(本周完成)</span>
                  </label>
                  <span class="text-sm text-gray">
                    {{ field.field_type === 'markdown' ? '支持Markdown' : (field.field_type === 'multiselect' ? '多选' : '单选') }}
                  </span>
                </div>

                <div v-if="field.field_type === 'markdown'">
                  <el-input
                    type="textarea"
                    :model-value="getFieldValue(field)"
                    @update:model-value="setFieldValue(field, $event)"
                    :placeholder="field.placeholder || '请输入...'"
                    :rows="7"
                    :disabled="report?.status === 'submitted'"
                    resize="vertical"
                    style="font-family:monospace;"
                  />
                  <div class="text-sm text-gray mt-10" v-if="previewMode && getFieldValue(field)">
                    <div style="padding:12px;background:#fafafa;border-radius:4px;" class="markdown-preview" v-html="renderMarkdown(getFieldValue(field))"></div>
                  </div>
                </div>

                <div v-else-if="field.field_type === 'select'">
                  <el-select
                    :model-value="getFieldValue(field)"
                    @update:model-value="setFieldValue(field, $event)"
                    placeholder="请选择" style="width:100%;"
                    :disabled="report?.status === 'submitted'"
                  >
                    <el-option v-for="opt in (field.options || [])" :key="opt.value"
                      :label="opt.label" :value="opt.value" />
                  </el-select>
                </div>

                <div v-else-if="field.field_type === 'multiselect'">
                  <el-checkbox-group
                    :model-value="getFieldValue(field)"
                    @update:model-value="setFieldValue(field, $event)"
                    :disabled="report?.status === 'submitted'"
                  >
                    <el-checkbox v-for="opt in (field.options || [])" :key="opt.value"
                      :label="opt.value" border style="margin:6px 12px 6px 0;">
                      {{ opt.label }}
                    </el-checkbox>
                  </el-checkbox-group>
                </div>

                <div v-else>
                  <el-input
                    :model-value="getFieldValue(field)"
                    @update:model-value="setFieldValue(field, $event)"
                    :placeholder="field.placeholder || '请输入...'"
                    :disabled="report?.status === 'submitted'"
                  />
                </div>
              </div>
            </template>
          </div>
        </div>
      </el-col>

      <el-col :span="12" v-if="previewMode">
        <div class="card" style="position:sticky;top:16px;">
          <h3 style="margin-bottom:16px;padding-bottom:12px;border-bottom:1px solid #ebeef5;">📖 实时预览</h3>
          <div class="markdown-preview">
            <h2>本周周报 · {{ weekDisplay }}</h2>
            <div v-for="field in (template?.fields || []).sort((a,b)=>a.sort_order-b.sort_order)" :key="field.field_key">
              <h3 style="color:#374151;">
                {{ field.field_name }}
                <span v-if="field.is_risk_field" class="text-red">⚠️</span>
              </h3>
              <div v-if="field.field_type === 'markdown'" v-html="renderMarkdown(getFieldValue(field))"
                :class="field.is_risk_field && getFieldValue(field) ? 'risk-highlight' : ''"></div>
              <div v-else-if="field.field_type === 'multiselect'">
                <el-tag v-for="v in getFieldValue(field)" :key="v" style="margin:4px 8px 4px 0;">
                  {{ field.options?.find(o=>o.value===v)?.label || v }}
                </el-tag>
                <span v-if="!getFieldValue(field).length" class="text-gray">未选择</span>
              </div>
              <div v-else>
                <span v-if="getFieldValue(field)">{{ field.options?.find(o=>o.value===getFieldValue(field))?.label || getFieldValue(field) }}</span>
                <span v-else class="text-gray">未填写</span>
              </div>
            </div>
          </div>
        </div>
      </el-col>
    </el-row>
  </div>
</template>
