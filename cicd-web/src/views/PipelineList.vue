<template>
  <div class="page-container">
    <div class="page-header">
      <h2 class="page-title">流水线管理</h2>
      <div>
        <el-button type="primary" :icon="Plus" @click="showCreateDialog = true">
          新建流水线
        </el-button>
      </div>
    </div>

    <div class="card">
      <el-table :data="pipelines" v-loading="loading">
        <el-table-column prop="name" label="名称" width="200" />
        <el-table-column prop="description" label="描述" />
        <el-table-column label="最近执行" width="180">
          <template #default="{ row }">
            <span v-if="row.lastExecution">
              #{{ row.lastExecution.executionNumber }}
              <span :class="['status-tag', 'status-' + row.lastExecution.status?.toLowerCase()]" style="margin-left: 8px">
                {{ getStatusText(row.lastExecution.status) }}
              </span>
            </span>
            <span v-else class="text-placeholder">-</span>
          </template>
        </el-table-column>
        <el-table-column label="创建时间" width="180">
          <template #default="{ row }">
            {{ formatTime(row.createdAt) }}
          </template>
        </el-table-column>
        <el-table-column label="操作" width="280" fixed="right">
          <template #default="{ row }">
            <el-button type="primary" link @click="triggerPipeline(row)">运行</el-button>
            <el-button link @click="viewPipeline(row)">详情</el-button>
            <el-button link @click="editPipeline(row)">编辑</el-button>
            <el-button type="danger" link @click="deletePipeline(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
    </div>

    <el-dialog v-model="showCreateDialog" title="新建流水线" width="800px">
      <el-form :model="pipelineForm" label-width="100px">
        <el-form-item label="选择模板">
          <el-select v-model="selectedTemplate" @change="onTemplateChange" placeholder="选择模板">
            <el-option label="空白模板" :value="null" />
            <el-option
              v-for="template in templates"
              :key="template.id"
              :label="template.name"
              :value="template"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="名称" prop="name">
          <el-input v-model="pipelineForm.name" placeholder="请输入流水线名称" />
        </el-form-item>
        <el-form-item label="描述">
          <el-input v-model="pipelineForm.description" type="textarea" :rows="2" />
        </el-form-item>
        <el-form-item label="YAML定义">
          <div class="yaml-editor">
            <textarea
              v-model="pipelineForm.yamlDefinition"
              class="yaml-textarea"
              spellcheck="false"
              placeholder="输入YAML流水线定义..."
            ></textarea>
          </div>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="showCreateDialog = false">取消</el-button>
        <el-button type="primary" @click="validateYaml">验证</el-button>
        <el-button type="success" @click="createPipeline">创建</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="showTriggerDialog" title="手动触发流水线" width="500px">
      <el-form :model="triggerForm" label-width="100px">
        <el-form-item label="分支">
          <el-input v-model="triggerForm.branchName" placeholder="如: main, release/v1.0" />
        </el-form-item>
        <el-form-item label="参数">
          <div v-for="(value, key) in triggerForm.params" :key="key" class="param-item">
            <span style="width: 100px; display: inline-block">{{ key }}:</span>
            <el-input v-model="triggerForm.params[key]" style="width: 300px" />
          </div>
          <el-button type="text" size="small" @click="addParam">+ 添加参数</el-button>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="showTriggerDialog = false">取消</el-button>
        <el-button type="primary" @click="confirmTrigger">确认运行</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, onMounted, reactive } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus } from '@element-plus/icons-vue'
import { pipelineAPI } from '@/api'
import { useUserStore } from '@/store/user'
import dayjs from 'dayjs'

const router = useRouter()
const userStore = useUserStore()

const loading = ref(false)
const pipelines = ref([])
const templates = ref([])
const showCreateDialog = ref(false)
const showTriggerDialog = ref(false)
const selectedTemplate = ref(null)
const currentPipeline = ref(null)

const pipelineForm = reactive({
  name: '',
  description: '',
  yamlDefinition: ''
})

const triggerForm = reactive({
  branchName: 'main',
  params: {}
})

const loadPipelines = async () => {
  loading.value = true
  try {
    const data = await pipelineAPI.list(userStore.currentProject.id)
    pipelines.value = data.content || data || []
  } catch (e) {
    console.error(e)
  } finally {
    loading.value = false
  }
}

const loadTemplates = async () => {
  try {
    templates.value = await pipelineAPI.listTemplates()
  } catch (e) {
    console.error(e)
  }
}

const onTemplateChange = (template) => {
  if (template) {
    pipelineForm.yamlDefinition = template.yamlDefinition
    pipelineForm.name = template.name + ' - ' + Date.now()
  } else {
    pipelineForm.yamlDefinition = `name: 新流水线
description: 流水线描述
params:
  - name: 环境
    type: string
    default: dev
stages:
  - name: 构建
    jobs:
      - name: maven构建
        steps:
          - type: script
            script: mvn clean package
`
  }
}

const validateYaml = async () => {
  try {
    await pipelineAPI.validateYaml(pipelineForm.yamlDefinition)
    ElMessage.success('YAML格式验证通过')
  } catch (e) {
    ElMessage.error('YAML格式错误: ' + e.message)
  }
}

const createPipeline = async () => {
  try {
    await pipelineAPI.create({
      projectId: userStore.currentProject.id,
      ...pipelineForm
    })
    ElMessage.success('创建成功')
    showCreateDialog.value = false
    loadPipelines()
  } catch (e) {
    console.error(e)
  }
}

const triggerPipeline = (row) => {
  currentPipeline.value = row
  triggerForm.branchName = 'main'
  triggerForm.params = {}
  showTriggerDialog.value = true
}

const addParam = () => {
  const key = 'param' + (Object.keys(triggerForm.params).length + 1)
  triggerForm.params[key] = ''
}

const confirmTrigger = async () => {
  try {
    const result = await pipelineAPI.trigger(currentPipeline.value.id, {
      branchName: triggerForm.branchName,
      params: triggerForm.params,
      triggeredBy: userStore.userInfo?.username
    })
    ElMessage.success('流水线已触发')
    showTriggerDialog.value = false
    router.push(`/pipelines/${currentPipeline.value.id}/executions/${result.id}`)
  } catch (e) {
    console.error(e)
  }
}

const viewPipeline = (row) => {
  router.push(`/pipelines/${row.id}`)
}

const editPipeline = (row) => {
  router.push(`/pipelines/${row.id}/edit`)
}

const deletePipeline = async (row) => {
  try {
    await ElMessageBox.confirm(`确定要删除流水线"${row.name}"吗？`, '提示', {
      type: 'warning'
    })
    await pipelineAPI.delete(row.id)
    ElMessage.success('删除成功')
    loadPipelines()
  } catch (e) {
    if (e !== 'cancel') {
      console.error(e)
    }
  }
}

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

onMounted(() => {
  loadPipelines()
  loadTemplates()
})
</script>

<style scoped lang="scss">
.yaml-editor {
  border: 1px solid #dcdfe6;
  border-radius: 4px;
  overflow: hidden;
}

.yaml-textarea {
  width: 100%;
  min-height: 400px;
  padding: 12px;
  font-family: 'Monaco', 'Menlo', monospace;
  font-size: 13px;
  line-height: 1.6;
  border: none;
  outline: none;
  resize: vertical;
}

.param-item {
  margin-bottom: 8px;
}

.text-placeholder {
  color: #909399;
}
</style>
