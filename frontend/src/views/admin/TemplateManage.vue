<script setup>
import { ref, onMounted, reactive } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { templateApi } from '@/api'

const loading = ref(false)
const templates = ref([])

const dialog = ref(false)
const form = reactive({
  id: null,
  name: '',
  description: '',
  is_default: false,
  fields: []
})

const versions = ref([])
const versionsDialog = ref(false)

async function loadList() {
  loading.value = true
  try {
    templates.value = await templateApi.list()
  } finally {
    loading.value = false
  }
}

function emptyField() {
  return {
    field_key: '',
    field_name: '',
    field_type: 'markdown',
    options: [],
    placeholder: '',
    is_required: true,
    sort_order: 0,
    is_risk_field: false,
    is_plan_field: false,
    is_achievement_field: false
  }
}

function openCreate() {
  form.id = null
  form.name = '新建周报模板'
  form.description = ''
  form.is_default = false
  form.fields = [
    { ...emptyField(), field_key: 'week_achievement', field_name: '本周完成工作', is_required: true, is_achievement_field: true, sort_order: 1, field_type: 'markdown', placeholder: '分点列出本周主要完成的工作' },
    { ...emptyField(), field_key: 'next_plan', field_name: '下周工作计划', is_required: true, is_plan_field: true, sort_order: 2, field_type: 'markdown', placeholder: '分点列出下周计划完成的工作' },
    { ...emptyField(), field_key: 'risk_block', field_name: '风险与阻塞', is_required: false, is_risk_field: true, sort_order: 3, field_type: 'markdown', placeholder: '如有风险或阻塞项请填写，无则填无' }
  ]
  dialog.value = true
}

async function openEdit(t) {
  const detail = await templateApi.get(t.id)
  form.id = t.id
  form.name = detail.name
  form.description = detail.description
  form.is_default = detail.is_default
  form.fields = (detail.fields || []).map(f => ({
    field_key: f.field_key,
    field_name: f.field_name,
    field_type: f.field_type,
    options: f.options || [],
    placeholder: f.placeholder || '',
    is_required: f.is_required,
    sort_order: f.sort_order,
    is_risk_field: f.is_risk_field,
    is_plan_field: f.is_plan_field,
    is_achievement_field: f.is_achievement_field
  }))
  dialog.value = true
}

function addField() {
  form.fields.push({ ...emptyField(), sort_order: (form.fields.at(-1)?.sort_order || 0) + 1 })
}

function removeField(idx) {
  form.fields.splice(idx, 1)
}

function moveField(idx, dir) {
  const i2 = idx + dir
  if (i2 < 0 || i2 >= form.fields.length) return
  const a = form.fields[idx], b = form.fields[i2]
  form.fields[idx] = b
  form.fields[i2] = a
  form.fields.forEach((f, i) => f.sort_order = i + 1)
}

function addOption(fi) {
  form.fields[fi].options.push({ label: '', value: '' })
}
function removeOption(fi, oi) {
  form.fields[fi].options.splice(oi, 1)
}

async function save() {
  if (!form.name.trim()) return ElMessage.warning('请填写模板名称')
  if (!form.fields.length) return ElMessage.warning('至少添加一个字段')
  for (const f of form.fields) {
    if (!f.field_key.trim() || !f.field_name.trim()) {
      return ElMessage.warning('所有字段需填写键名和显示名')
    }
  }
  try {
    if (form.id) {
      await templateApi.update(form.id, {
        name: form.name,
        description: form.description,
        is_default: form.is_default,
        fields: form.fields,
        change_note: `版本更新于 ${new Date().toLocaleString()}`
      })
      ElMessage.success('模板已更新（新版本已创建，历史周报不受影响）')
    } else {
      await templateApi.create({
        name: form.name,
        description: form.description,
        is_default: form.is_default,
        fields: form.fields
      })
      ElMessage.success('模板创建成功')
    }
    dialog.value = false
    loadList()
  } catch (e) {
    if (e?.response?.data?.detail) ElMessage.error(e.response.data.detail)
  }
}

async function handleDelete(t) {
  await ElMessageBox.confirm(`确定删除模板【${t.name}】吗？`, '删除确认', { type: 'error' })
  try {
    await templateApi.remove(t.id)
    ElMessage.success('已处理')
    loadList()
  } catch (e) {
    if (e?.response?.data?.message) ElMessage.warning(e.response.data.message)
  }
}

async function viewVersions(t) {
  versions.value = await templateApi.versions(t.id)
  versionsDialog.value = true
}

onMounted(loadList)
</script>

<template>
  <div class="page-container">
    <div class="page-header">
      <div>
        <h2>📄 模板管理</h2>
        <div class="text-sm text-gray mt-10">自定义周报模板结构，支持版本管理</div>
      </div>
      <el-button type="primary" @click="openCreate">+ 新建模板</el-button>
    </div>

    <div class="card" v-loading="loading">
      <el-table :data="templates" stripe>
        <el-table-column label="#" width="60" type="index" />
        <el-table-column label="模板名称" min-width="180">
          <template #default="{row}">
            <div style="font-weight:500;">
              <el-tag v-if="row.is_default" type="success" size="small" style="margin-right:6px;">默认</el-tag>
              {{ row.name }}
              <el-tag v-if="!row.is_active" type="info" size="small" style="margin-left:6px;">已停用</el-tag>
            </div>
            <div class="text-sm text-gray mt-10" v-if="row.description">{{ row.description }}</div>
          </template>
        </el-table-column>
        <el-table-column label="字段数" width="90" align="center">
          <template #default="{row}">
            <el-tag type="info" size="small">{{ row.fields?.length || 0 }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="当前版本" width="100" align="center">
          <template #default="{row}">v{{ row.current_version }}</template>
        </el-table-column>
        <el-table-column label="创建时间" width="170">
          <template #default="{row}">{{ row.created_at?.slice(0,16).replace('T',' ') }}</template>
        </el-table-column>
        <el-table-column label="字段预览" min-width="280">
          <template #default="{row}">
            <div style="display:flex;flex-wrap:wrap;gap:6px;">
              <el-tag v-for="f in (row.fields||[]).slice(0,5)" :key="f.id" size="small"
                :type="f.is_required ? 'primary' : 'info'" effect="plain">
                {{ f.field_name }}
                <span v-if="f.is_required" class="text-red">*</span>
              </el-tag>
              <el-tag v-if="(row.fields||[]).length>5" size="small" type="info">+{{ row.fields.length - 5 }}个</el-tag>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="220" fixed="right">
          <template #default="{row}">
            <el-button size="small" @click="openEdit(row)">编辑</el-button>
            <el-button size="small" type="info" @click="viewVersions(row)">版本</el-button>
            <el-button size="small" type="danger" @click="handleDelete(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
    </div>

    <el-dialog v-model="dialog" :title="form.id ? '编辑模板' : '新建模板'" width="920px" top="5vh">
      <el-form label-width="110px">
        <el-row :gutter="16">
          <el-col :span="12">
            <el-form-item label="模板名称 *">
              <el-input v-model="form.name" />
            </el-form-item>
          </el-col>
          <el-col :span="6">
            <el-form-item label="默认模板">
              <el-switch v-model="form.is_default" />
            </el-form-item>
          </el-col>
          <el-col :span="24">
            <el-form-item label="模板描述">
              <el-input v-model="form.description" placeholder="可选，说明模板用途" />
            </el-form-item>
          </el-col>
        </el-row>

        <div class="flex-between mb-10" style="padding:8px 0;border-top:1px dashed #ebeef5;">
          <h4 style="font-size:14px;">📋 字段配置（拖拽或点击箭头调整顺序）</h4>
          <el-button type="primary" size="small" @click="addField">+ 添加字段</el-button>
        </div>

        <div v-for="(f, idx) in form.fields" :key="idx"
          style="border:1px solid #ebeef5;border-radius:8px;padding:14px;margin-bottom:14px;background:#fafbfc;">
          <div class="flex-between mb-10">
            <div class="text-sm text-gray">字段 #{{ idx + 1 }}</div>
            <div style="display:flex;gap:6px;">
              <el-button size="small" :disabled="idx===0" @click="moveField(idx,-1)">↑</el-button>
              <el-button size="small" :disabled="idx===form.fields.length-1" @click="moveField(idx,1)">↓</el-button>
              <el-button size="small" type="danger" @click="removeField(idx)">删除</el-button>
            </div>
          </div>
          <el-row :gutter="12">
            <el-col :span="6">
              <el-form-item label="字段键" label-width="64px">
                <el-input v-model="f.field_key" placeholder="如 week_achievement" size="small" />
              </el-form-item>
            </el-col>
            <el-col :span="6">
              <el-form-item label="显示名" label-width="54px">
                <el-input v-model="f.field_name" placeholder="如 本周完成工作" size="small" />
              </el-form-item>
            </el-col>
            <el-col :span="5">
              <el-form-item label="类型" label-width="36px">
                <el-select v-model="f.field_type" size="small" style="width:100%;">
                  <el-option label="Markdown文本" value="markdown" />
                  <el-option label="单选" value="select" />
                  <el-option label="多选" value="multiselect" />
                  <el-option label="普通文本" value="text" />
                </el-select>
              </el-form-item>
            </el-col>
            <el-col :span="3" style="text-align:center;">
              <el-form-item label="必填">
                <el-switch v-model="f.is_required" size="small" />
              </el-form-item>
            </el-col>
            <el-col :span="4" style="display:flex;justify-content:space-around;align-items:center;">
              <el-tooltip content="作为本周完成字段（用于计划偏离对比）">
                <el-checkbox v-model="f.is_achievement_field" size="small">完成</el-checkbox>
              </el-tooltip>
              <el-tooltip content="作为下周计划字段">
                <el-checkbox v-model="f.is_plan_field" size="small">计划</el-checkbox>
              </el-tooltip>
              <el-tooltip content="作为风险字段（汇总标红）">
                <el-checkbox v-model="f.is_risk_field" size="small">风险</el-checkbox>
              </el-tooltip>
            </el-col>
            <el-col :span="24">
              <el-form-item label="占位提示" label-width="64px">
                <el-input v-model="f.placeholder" size="small" />
              </el-form-item>
            </el-col>
            <el-col :span="24" v-if="['select','multiselect'].includes(f.field_type)">
              <div style="display:flex;align-items:center;justify-content:space-between;margin-bottom:6px;">
                <span class="text-sm text-gray">选项配置：</span>
                <el-button size="small" @click="addOption(idx)">+ 增加选项</el-button>
              </div>
              <div v-for="(opt, oi) in f.options" :key="oi" style="display:flex;gap:8px;margin-bottom:6px;">
                <el-input v-model="opt.label" placeholder="显示标签" size="small" style="flex:1;" />
                <el-input v-model="opt.value" placeholder="值" size="small" style="flex:1;" />
                <el-button size="small" type="danger" @click="removeOption(idx, oi)">删</el-button>
              </div>
              <div v-if="!f.options.length" class="text-sm text-gray">暂无选项</div>
            </el-col>
          </el-row>
        </div>
      </el-form>
      <template #footer>
        <el-button @click="dialog = false">取消</el-button>
        <el-button type="primary" @click="save">💾 保存（自动创建新版本）</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="versionsDialog" title="版本历史" width="680px">
      <el-empty v-if="!versions.length" description="暂无版本记录" />
      <el-timeline v-else>
        <el-timeline-item v-for="v in versions" :key="v.id" :timestamp="v.created_at?.slice(0,19).replace('T',' ')">
          <h4 style="font-size:14px;">
            <el-tag type="primary" size="small" style="margin-right:8px;">v{{ v.version }}</el-tag>
            {{ v.change_note || '无更新说明' }}
          </h4>
          <div style="margin-top:8px;">
            <span class="text-sm text-gray">包含字段：</span>
            <el-tag v-for="f in (v.fields_snapshot||[]).slice(0,8)" :key="f.field_key" size="small" style="margin:3px;">
              {{ f.field_name }}
              <span v-if="f.is_required" class="text-red">*</span>
            </el-tag>
          </div>
        </el-timeline-item>
      </el-timeline>
    </el-dialog>
  </div>
</template>
