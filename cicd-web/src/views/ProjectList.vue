<template>
  <div class="page-container">
    <div class="page-header">
      <h2 class="page-title">项目管理</h2>
      <div>
        <el-button type="primary" :icon="Plus" @click="showCreateDialog = true">
          新建项目
        </el-button>
      </div>
    </div>

    <div class="card">
      <el-table :data="projects" v-loading="loading">
        <el-table-column label="ID" width="80">
          <template #default="{ row }">
            #{{ row.id }}
          </template>
        </el-table-column>
        <el-table-column prop="name" label="项目名称" width="200" />
        <el-table-column prop="code" label="项目标识" width="150" />
        <el-table-column prop="description" label="描述" />
        <el-table-column label="Git仓库" width="250">
          <template #default="{ row }">
            <el-link :href="row.gitUrl" type="primary" target="_blank" v-if="row.gitUrl">
              {{ row.gitUrl }}
            </el-link>
            <span v-else class="text-placeholder">-</span>
          </template>
        </el-table-column>
        <el-table-column label="流水线数" width="100" align="center">
          <template #default="{ row }">
            {{ row.pipelineCount || 0 }}
          </template>
        </el-table-column>
        <el-table-column label="Owner" width="150">
          <template #default="{ row }">
            <el-tag v-if="row.owner" size="small" type="primary">{{ row.owner }}</el-tag>
            <span v-else class="text-placeholder">-</span>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <el-tag :type="row.active ? 'success' : 'info'" size="small">
              {{ row.active ? '启用' : '禁用' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="创建时间" width="180">
          <template #default="{ row }">
            {{ formatTime(row.createdAt) }}
          </template>
        </el-table-column>
        <el-table-column label="操作" width="200" fixed="right">
          <template #default="{ row }">
            <el-button type="primary" link @click="selectProject(row)">进入</el-button>
            <el-button link @click="editProject(row)">编辑</el-button>
            <el-button type="danger" link @click="deleteProject(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
    </div>

    <el-dialog v-model="showCreateDialog" :title="editingProject ? '编辑项目' : '新建项目'" width="600px">
      <el-form :model="projectForm" label-width="120px">
        <el-form-item label="项目名称" prop="name">
          <el-input v-model="projectForm.name" placeholder="请输入项目名称" />
        </el-form-item>
        <el-form-item label="项目标识" prop="code">
          <el-input v-model="projectForm.code" placeholder="如: user-service, order-service" />
          <div class="form-tip">项目标识用于URL路径和命名空间，创建后不可修改</div>
        </el-form-item>
        <el-form-item label="描述">
          <el-input v-model="projectForm.description" type="textarea" :rows="2" />
        </el-form-item>
        <el-form-item label="Git仓库地址">
          <el-input v-model="projectForm.gitUrl" placeholder="https://gitlab.com/group/project.git" />
        </el-form-item>
        <el-form-item label="Git认证方式">
          <el-select v-model="projectForm.gitAuthType">
            <el-option label="无需认证" value="NONE" />
            <el-option label="HTTP/HTTPS" value="HTTP" />
            <el-option label="SSH密钥" value="SSH" />
          </el-select>
        </el-form-item>
        <el-form-item label="Git用户名" v-if="projectForm.gitAuthType === 'HTTP'">
          <el-input v-model="projectForm.gitUsername" placeholder="Git用户名" />
        </el-form-item>
        <el-form-item label="Git Token/密码" v-if="projectForm.gitAuthType === 'HTTP'">
          <el-input v-model="projectForm.gitToken" type="password" show-password placeholder="Token或密码" />
        </el-form-item>
        <el-form-item label="SSH私钥" v-if="projectForm.gitAuthType === 'SSH'">
          <el-input v-model="projectForm.gitSshKey" type="textarea" :rows="4" placeholder="-----BEGIN RSA PRIVATE KEY-----..." />
        </el-form-item>
        <el-form-item label="Webhook密钥">
          <el-input v-model="projectForm.webhookSecret" placeholder="用于验证Webhook请求签名" />
        </el-form-item>
        <el-form-item label="项目Owner">
          <el-select v-model="projectForm.owner" filterable placeholder="选择项目Owner">
            <el-option label="admin" value="admin" />
            <el-option label="developer1" value="developer1" />
            <el-option label="developer2" value="developer2" />
          </el-select>
        </el-form-item>
        <el-form-item label="启用项目">
          <el-switch v-model="projectForm.active" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="showCreateDialog = false">取消</el-button>
        <el-button type="primary" @click="saveProject">保存</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="showMembersDialog" title="项目成员管理" width="600px">
      <div class="members-header">
        <h4>{{ currentProject?.name }} - 成员列表</h4>
        <el-button type="primary" size="small" :icon="Plus">添加成员</el-button>
      </div>
      <el-table :data="projectMembers" border>
        <el-table-column prop="username" label="用户名" width="150" />
        <el-table-column prop="role" label="角色" width="150">
          <template #default="{ row }">
            <el-select v-model="row.role" size="small">
              <el-option label="项目Owner" value="PROJECT_OWNER" />
              <el-option label="开发者" value="DEVELOPER" />
              <el-option label="只读" value="VIEWER" />
            </el-select>
          </template>
        </el-table-column>
        <el-table-column prop="email" label="邮箱" />
        <el-table-column label="加入时间" width="180">
          <template #default="{ row }">
            {{ formatTime(row.joinedAt) }}
          </template>
        </el-table-column>
        <el-table-column label="操作" width="80" align="center">
          <template #default>
            <el-button type="danger" link size="small">移除</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, reactive, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus } from '@element-plus/icons-vue'
import { projectAPI } from '@/api'
import { useUserStore } from '@/store/user'
import dayjs from 'dayjs'

const router = useRouter()
const userStore = useUserStore()

const loading = ref(false)
const projects = ref([])
const showCreateDialog = ref(false)
const showMembersDialog = ref(false)
const editingProject = ref(null)
const currentProject = ref(null)
const projectMembers = ref([])

const projectForm = reactive({
  name: '',
  code: '',
  description: '',
  gitUrl: '',
  gitAuthType: 'NONE',
  gitUsername: '',
  gitToken: '',
  gitSshKey: '',
  webhookSecret: '',
  owner: '',
  active: true
})

const loadProjects = async () => {
  loading.value = true
  try {
    const data = await projectAPI.list({ page: 0, size: 50 })
    projects.value = data.content || data || []
  } catch (e) {
    console.error(e)
  } finally {
    loading.value = false
  }
}

const selectProject = (row) => {
  userStore.setCurrentProject(row)
  ElMessage.success(`已切换到项目: ${row.name}`)
  router.push('/dashboard')
}

const editProject = (row) => {
  editingProject.value = row
  Object.assign(projectForm, row)
  showCreateDialog.value = true
}

const saveProject = async () => {
  try {
    if (editingProject.value) {
      await projectAPI.update(editingProject.value.id, projectForm)
      ElMessage.success('更新成功')
    } else {
      await projectAPI.create(projectForm)
      ElMessage.success('创建成功')
    }
    showCreateDialog.value = false
    resetForm()
    loadProjects()
  } catch (e) {
    console.error(e)
  }
}

const deleteProject = async (row) => {
  try {
    await ElMessageBox.confirm(
      `确定要删除项目"${row.name}"吗？此操作会删除项目下的所有流水线和历史数据。`,
      '提示',
      { type: 'warning' }
    )
    await projectAPI.delete(row.id)
    ElMessage.success('删除成功')
    loadProjects()
  } catch (e) {
    if (e !== 'cancel') {
      console.error(e)
    }
  }
}

const resetForm = () => {
  editingProject.value = null
  projectForm.name = ''
  projectForm.code = ''
  projectForm.description = ''
  projectForm.gitUrl = ''
  projectForm.gitAuthType = 'NONE'
  projectForm.gitUsername = ''
  projectForm.gitToken = ''
  projectForm.gitSshKey = ''
  projectForm.webhookSecret = ''
  projectForm.owner = ''
  projectForm.active = true
}

const formatTime = (time) => {
  return time ? dayjs(time).format('YYYY-MM-DD HH:mm:ss') : '-'
}

onMounted(() => {
  loadProjects()
})
</script>

<style scoped lang="scss">
.form-tip {
  margin-top: 4px;
  font-size: 12px;
  color: #909399;
}

.text-placeholder {
  color: #909399;
}

.members-header {
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
