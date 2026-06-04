<template>
  <div class="page-container">
    <div class="page-header">
      <h2 class="page-title">环境管理</h2>
      <div>
        <el-button type="primary" :icon="Plus" @click="showCreateDialog = true">
          新建环境
        </el-button>
      </div>
    </div>

    <div class="env-grid">
      <div
        v-for="env in environments"
        :key="env.id"
        class="env-card"
        :class="`env-${env.name.toLowerCase()}`"
      >
        <div class="env-header">
          <div class="env-title">
            <el-tag :type="getEnvTagType(env.name)" size="large" effect="dark">
              {{ env.name }}
            </el-tag>
            <h3>{{ env.description }}</h3>
          </div>
          <div class="env-actions">
            <el-dropdown @command="(cmd) => handleAction(cmd, env)">
              <el-button size="small">
                <el-icon><MoreFilled /></el-icon>
              </el-button>
              <template #dropdown>
                <el-dropdown-menu>
                  <el-dropdown-item command="edit">编辑</el-dropdown-item>
                  <el-dropdown-item command="variables">变量管理</el-dropdown-item>
                  <el-dropdown-item command="delete" divided>删除</el-dropdown-item>
                </el-dropdown-menu>
              </template>
            </el-dropdown>
          </div>
        </div>

        <div class="env-info">
          <div class="info-item">
            <span class="label">集群</span>
            <span class="value">{{ env.k8sCluster || '-' }}</span>
          </div>
          <div class="info-item">
            <span class="label">Namespace</span>
            <span class="value">{{ env.k8sNamespace || '-' }}</span>
          </div>
          <div class="info-item">
            <span class="label">API地址</span>
            <span class="value">{{ env.apiUrl || '-' }}</span>
          </div>
          <div class="info-item">
            <span class="label">部署策略</span>
            <span class="value">{{ getStrategyText(env.deploymentStrategy) }}</span>
          </div>
          <div class="info-item">
            <span class="label">需要审批</span>
            <span class="value">
              <el-tag :type="env.requireApproval ? 'warning' : 'success'" size="small">
                {{ env.requireApproval ? '是' : '否' }}
              </el-tag>
            </span>
          </div>
        </div>

        <div class="env-footer">
          <div class="variable-count">
            <el-icon><Setting /></el-icon>
            环境变量: {{ env.variables?.length || 0 }} 个
          </div>
          <div class="update-time">
            更新于 {{ formatTime(env.updatedAt) }}
          </div>
        </div>
      </div>
    </div>

    <el-dialog v-model="showCreateDialog" :title="editingEnv ? '编辑环境' : '新建环境'" width="600px">
      <el-form :model="envForm" label-width="120px">
        <el-form-item label="环境名称" prop="name">
          <el-select v-model="envForm.name" placeholder="选择环境">
            <el-option label="开发 (dev)" value="dev" />
            <el-option label="测试 (test)" value="test" />
            <el-option label="预发布 (staging)" value="staging" />
            <el-option label="生产 (prod)" value="prod" />
            <el-option label="自定义" value="custom" />
          </el-select>
        </el-form-item>
        <el-form-item label="描述" prop="description">
          <el-input v-model="envForm.description" placeholder="环境描述" />
        </el-form-item>
        <el-form-item label="K8s集群">
          <el-input v-model="envForm.k8sCluster" placeholder="集群名称或地址" />
        </el-form-item>
        <el-form-item label="K8s Namespace">
          <el-input v-model="envForm.k8sNamespace" placeholder="如: default, production" />
        </el-form-item>
        <el-form-item label="API地址">
          <el-input v-model="envForm.apiUrl" placeholder="https://api.example.com" />
        </el-form-item>
        <el-form-item label="部署策略">
          <el-select v-model="envForm.deploymentStrategy">
            <el-option label="滚动更新" value="ROLLING" />
            <el-option label="蓝绿部署" value="BLUE_GREEN" />
            <el-option label="金丝雀发布" value="CANARY" />
          </el-select>
        </el-form-item>
        <el-form-item label="需要审批">
          <el-switch v-model="envForm.requireApproval" />
        </el-form-item>
        <el-form-item label="审批人" v-if="envForm.requireApproval">
          <el-select v-model="envForm.approvers" multiple filterable placeholder="选择审批人">
            <el-option label="admin" value="admin" />
            <el-option label="manager1" value="manager1" />
            <el-option label="manager2" value="manager2" />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="showCreateDialog = false">取消</el-button>
        <el-button type="primary" @click="saveEnvironment">保存</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="showVariablesDialog" title="环境变量管理" width="700px">
      <div class="variables-header">
        <h4>{{ currentEnv?.name }} - 环境变量</h4>
        <el-button type="primary" size="small" :icon="Plus" @click="addVariable">
          添加变量
        </el-button>
      </div>
      <el-table :data="envVariables" border>
        <el-table-column prop="key" label="变量名" width="180">
          <template #default="{ row }">
            <el-input v-model="row.key" size="small" />
          </template>
        </el-table-column>
        <el-table-column prop="value" label="变量值">
          <template #default="{ row }">
            <el-input
              v-model="row.value"
              size="small"
              :type="row.sensitive ? 'password' : 'input'"
              show-password
            />
          </template>
        </el-table-column>
        <el-table-column label="加密" width="100" align="center">
          <template #default="{ row }">
            <el-switch v-model="row.sensitive" size="small" />
          </template>
        </el-table-column>
        <el-table-column label="操作" width="80" align="center">
          <template #default="{ $index }">
            <el-button type="danger" link size="small" @click="removeVariable($index)">
              删除
            </el-button>
          </template>
        </el-table-column>
      </el-table>
      <template #footer>
        <el-button @click="showVariablesDialog = false">取消</el-button>
        <el-button type="primary" @click="saveVariables">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, reactive, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus, MoreFilled, Setting } from '@element-plus/icons-vue'
import { environmentAPI } from '@/api'
import { useUserStore } from '@/store/user'
import dayjs from 'dayjs'

const userStore = useUserStore()

const loading = ref(false)
const environments = ref([])
const showCreateDialog = ref(false)
const showVariablesDialog = ref(false)
const editingEnv = ref(null)
const currentEnv = ref(null)
const envVariables = ref([])

const envForm = reactive({
  name: '',
  description: '',
  k8sCluster: '',
  k8sNamespace: '',
  apiUrl: '',
  deploymentStrategy: 'ROLLING',
  requireApproval: false,
  approvers: []
})

const loadEnvironments = async () => {
  loading.value = true
  try {
    environments.value = await environmentAPI.list(userStore.currentProject?.id)
  } catch (e) {
    console.error(e)
  } finally {
    loading.value = false
  }
}

const handleAction = (cmd, env) => {
  if (cmd === 'edit') {
    editEnvironment(env)
  } else if (cmd === 'variables') {
    manageVariables(env)
  } else if (cmd === 'delete') {
    deleteEnvironment(env)
  }
}

const editEnvironment = (env) => {
  editingEnv.value = env
  Object.assign(envForm, env)
  showCreateDialog.value = true
}

const manageVariables = (env) => {
  currentEnv.value = env
  envVariables.value = env.variables?.map(v => ({ ...v })) || []
  showVariablesDialog.value = true
}

const addVariable = () => {
  envVariables.value.push({
    key: '',
    value: '',
    sensitive: false
  })
}

const removeVariable = (index) => {
  envVariables.value.splice(index, 1)
}

const saveVariables = async () => {
  try {
    await environmentAPI.update(currentEnv.value.id, {
      variables: envVariables.value
    })
    ElMessage.success('保存成功')
    showVariablesDialog.value = false
    loadEnvironments()
  } catch (e) {
    console.error(e)
  }
}

const saveEnvironment = async () => {
  try {
    if (editingEnv.value) {
      await environmentAPI.update(editingEnv.value.id, envForm)
      ElMessage.success('更新成功')
    } else {
      await environmentAPI.create({
        projectId: userStore.currentProject?.id,
        ...envForm
      })
      ElMessage.success('创建成功')
    }
    showCreateDialog.value = false
    loadEnvironments()
  } catch (e) {
    console.error(e)
  }
}

const deleteEnvironment = async (env) => {
  try {
    await ElMessageBox.confirm(
      `确定要删除环境"${env.name}"吗？`,
      '提示',
      { type: 'warning' }
    )
    await environmentAPI.delete(env.id)
    ElMessage.success('删除成功')
    loadEnvironments()
  } catch (e) {
    if (e !== 'cancel') {
      console.error(e)
    }
  }
}

const getEnvTagType = (name) => {
  const map = {
    'dev': 'success',
    'test': 'info',
    'staging': 'warning',
    'prod': 'danger'
  }
  return map[name?.toLowerCase()] || 'info'
}

const getStrategyText = (strategy) => {
  const map = {
    'ROLLING': '滚动更新',
    'BLUE_GREEN': '蓝绿部署',
    'CANARY': '金丝雀发布'
  }
  return map[strategy] || strategy || '-'
}

const formatTime = (time) => {
  return time ? dayjs(time).format('YYYY-MM-DD HH:mm') : '-'
}

onMounted(() => {
  loadEnvironments()
})
</script>

<style scoped lang="scss">
.env-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(380px, 1fr));
  gap: 20px;
}

.env-card {
  background: #fff;
  border-radius: 12px;
  border: 1px solid #e4e7ed;
  overflow: hidden;
  transition: all 0.3s;

  &:hover {
    box-shadow: 0 4px 12px rgba(0, 0, 0, 0.1);
    transform: translateY(-2px);
  }

  &.env-dev { border-top: 4px solid #67c23a; }
  &.env-test { border-top: 4px solid #909399; }
  &.env-staging { border-top: 4px solid #e6a23c; }
  &.env-prod { border-top: 4px solid #f56c6c; }
}

.env-header {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  padding: 20px;
  border-bottom: 1px solid #f0f2f5;
}

.env-title {
  h3 {
    margin: 8px 0 0 0;
    font-size: 16px;
    font-weight: 500;
  }
}

.env-info {
  padding: 16px 20px;
}

.info-item {
  display: flex;
  margin-bottom: 12px;
  font-size: 14px;

  &:last-child {
    margin-bottom: 0;
  }

  .label {
    width: 90px;
    color: #909399;
    flex-shrink: 0;
  }

  .value {
    flex: 1;
    color: #303133;
  }
}

.env-footer {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 12px 20px;
  background: #fafafa;
  font-size: 13px;
  color: #909399;

  .variable-count {
    display: flex;
    align-items: center;
    gap: 6px;
  }
}

.variables-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 16px;

  h4 {
    margin: 0;
    font-size: 16px;
  }
}
</style>
