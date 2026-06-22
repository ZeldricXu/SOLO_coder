<script setup>
import { ref, onMounted, reactive, computed } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { userApi, teamApi } from '@/api'
import { useUserStore } from '@/store/user'

const userStore = useUserStore()
const loading = ref(false)
const users = ref([])
const teams = ref([])
const keyword = ref('')
const filterRole = ref('')
const filterTeam = ref('')

const filtered = computed(() => {
  return users.value.filter(u => {
    if (keyword.value) {
      const kw = keyword.value.toLowerCase()
      if (!u.username.toLowerCase().includes(kw) &&
          !u.full_name.toLowerCase().includes(kw) &&
          !u.email.toLowerCase().includes(kw)) return false
    }
    if (filterRole.value && u.role !== filterRole.value) return false
    if (filterTeam.value && u.team_id != filterTeam.value) return false
    return true
  })
})

const dialog = ref(false)
const form = reactive({
  id: null, username: '', email: '', full_name: '', password: '',
  team_id: null, role: 'user', wecom_userid: '', feishu_open_id: '', is_active: true
})

const roleOptions = [
  { value: 'super_admin', label: '超级管理员', type: 'danger' },
  { value: 'admin', label: '管理员(TL)', type: 'warning' },
  { value: 'user', label: '普通成员', type: 'info' }
]

async function loadAll() {
  loading.value = true
  try {
    const [u, t] = await Promise.all([userApi.list(), teamApi.list()])
    users.value = u; teams.value = t
  } finally {
    loading.value = false
  }
}

function openCreate() {
  Object.assign(form, {
    id: null, username: '', email: '', full_name: '', password: '',
    team_id: null, role: 'user', wecom_userid: '', feishu_open_id: '', is_active: true
  })
  dialog.value = true
}

function openEdit(u) {
  Object.assign(form, {
    id: u.id, username: u.username, email: u.email, full_name: u.full_name, password: '',
    team_id: u.team_id, role: u.role, wecom_userid: u.wecom_userid || '',
    feishu_open_id: u.feishu_open_id || '', is_active: u.is_active
  })
  dialog.value = true
}

async function save() {
  if (!form.username.trim() || !form.full_name.trim() || !form.email) {
    return ElMessage.warning('请填写必填信息')
  }
  if (!form.id && !form.password) return ElMessage.warning('新建用户需设置初始密码')

  const payload = { ...form }
  if (!payload.password) delete payload.password

  try {
    if (form.id) {
      await userApi.update(form.id, payload)
      ElMessage.success('已更新')
    } else {
      await userApi.create(payload)
      ElMessage.success('创建成功')
    }
    dialog.value = false
    loadAll()
  } catch (e) {
    if (e?.response?.data?.detail) ElMessage.error(e.response.data.detail)
  }
}

async function handleDelete(u) {
  if (u.id === userStore.userInfo?.id) return ElMessage.warning('不能删除自己')
  await ElMessageBox.confirm(`确定删除用户【${u.full_name}】吗？`, '删除确认', { type: 'error' })
  try {
    await userApi.remove(u.id)
    ElMessage.success('已删除')
    loadAll()
  } catch (e) {
    if (e?.response?.data?.detail) ElMessage.warning(e.response.data.detail)
  }
}

function getTeamName(tid) {
  return teams.value.find(t => t.id === tid)?.name || '未分配'
}

onMounted(loadAll)
</script>

<template>
  <div class="page-container">
    <div class="page-header">
      <div>
        <h2>👤 用户管理</h2>
        <div class="text-sm text-gray mt-10">共 {{ users.length }} 个用户</div>
      </div>
      <el-button type="primary" @click="openCreate">+ 新建用户</el-button>
    </div>

    <div class="card mb-20">
      <el-row :gutter="16">
        <el-col :span="8">
          <el-input v-model="keyword" placeholder="搜索 用户名/姓名/邮箱" clearable :prefix-icon="'Search'" />
        </el-col>
        <el-col :span="6">
          <el-select v-model="filterRole" clearable placeholder="角色筛选" style="width:100%;">
            <el-option v-for="r in roleOptions" :key="r.value" :label="r.label" :value="r.value" />
          </el-select>
        </el-col>
        <el-col :span="6">
          <el-select v-model="filterTeam" clearable placeholder="团队筛选" style="width:100%;">
            <el-option v-for="t in teams" :key="t.id" :label="t.name" :value="t.id" />
          </el-select>
        </el-col>
        <el-col :span="4">
          <el-button @click="loadAll" style="width:100%;">🔄 刷新</el-button>
        </el-col>
      </el-row>
    </div>

    <div class="card" v-loading="loading">
      <el-table :data="filtered" stripe>
        <el-table-column label="#" width="60" type="index" />
        <el-table-column label="用户" min-width="200">
          <template #default="{row}">
            <div class="flex-center">
              <el-avatar :size="36" style="background:#409eff;margin-right:10px;">{{ row.full_name?.charAt(0) }}</el-avatar>
              <div>
                <div style="font-weight:500;">{{ row.full_name }}</div>
                <div class="text-sm text-gray">@{{ row.username }} · {{ row.email }}</div>
              </div>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="团队" width="130">
          <template #default="{row}">
            <el-tag size="small" type="info" effect="plain">{{ getTeamName(row.team_id) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="角色" width="130">
          <template #default="{row}">
            <el-tag v-for="r in roleOptions.filter(x=>x.value===row.role)" :key="row.role"
              :type="r.type" size="small">{{ r.label }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="企微ID" width="120">
          <template #default="{row}">
            <span v-if="row.wecom_userid" class="text-sm">{{ row.wecom_userid }}</span>
            <span v-else class="text-gray text-sm">未绑定</span>
          </template>
        </el-table-column>
        <el-table-column label="飞书ID" width="120">
          <template #default="{row}">
            <span v-if="row.feishu_open_id" class="text-sm">{{ row.feishu_open_id }}</span>
            <span v-else class="text-gray text-sm">未绑定</span>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="90">
          <template #default="{row}">
            <el-tag v-if="row.is_active" type="success" size="small">正常</el-tag>
            <el-tag v-else type="danger" size="small">禁用</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="注册时间" width="170">
          <template #default="{row}">{{ row.created_at?.slice(0,16).replace('T',' ') }}</template>
        </el-table-column>
        <el-table-column label="操作" width="160" fixed="right">
          <template #default="{row}">
            <el-button size="small" @click="openEdit(row)">编辑</el-button>
            <el-button size="small" type="danger" :disabled="row.id===userStore.userInfo?.id" @click="handleDelete(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
    </div>

    <el-dialog v-model="dialog" :title="form.id ? '编辑用户' : '新建用户'" width="560px">
      <el-form label-width="100px">
        <el-row :gutter="12">
          <el-col :span="12">
            <el-form-item label="用户名 *">
              <el-input v-model="form.username" :disabled="!!form.id" />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="姓名 *">
              <el-input v-model="form.full_name" />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="邮箱 *">
              <el-input v-model="form.email" />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item :label="form.id ? '重置密码' : '初始密码 *'">
              <el-input v-model="form.password" :placeholder="form.id ? '留空则不修改' : '请设置初始密码'" show-password />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="所属团队">
              <el-select v-model="form.team_id" clearable filterable style="width:100%;">
                <el-option v-for="t in teams" :key="t.id" :label="t.name" :value="t.id" />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="角色">
              <el-select v-model="form.role" style="width:100%;">
                <el-option v-for="r in roleOptions" :key="r.value" :label="r.label" :value="r.value" />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="企业微信ID">
              <el-input v-model="form.wecom_userid" placeholder="用于@提醒" />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="飞书OpenID">
              <el-input v-model="form.feishu_open_id" placeholder="用于@提醒" />
            </el-form-item>
          </el-col>
          <el-col :span="12" v-if="form.id">
            <el-form-item label="启用状态">
              <el-switch v-model="form.is_active" />
            </el-form-item>
          </el-col>
        </el-row>
      </el-form>
      <template #footer>
        <el-button @click="dialog = false">取消</el-button>
        <el-button type="primary" @click="save">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>
