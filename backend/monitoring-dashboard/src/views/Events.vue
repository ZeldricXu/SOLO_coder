<template>
  <div class="events-page">
    <div class="page-header">
      <h1>事件管理</h1>
      <div class="header-actions">
        <el-button type="primary" @click="openAddDialog">
          <el-icon><Plus /></el-icon>
          新增事件类型
        </el-button>
      </div>
    </div>
    
    <el-row :gutter="20" class="stats-row">
      <el-col :span="6">
        <el-card class="stat-card">
          <div class="stat-content">
            <div class="icon login-icon">
              <el-icon :size="24"><User /></el-icon>
            </div>
            <div class="info">
              <div class="value">{{ eventStats.login || 0 }}</div>
              <div class="label">登录事件</div>
            </div>
          </div>
        </el-card>
      </el-col>
      <el-col :span="6">
        <el-card class="stat-card">
          <div class="stat-content">
            <div class="icon payment-icon">
              <el-icon :size="24"><Money /></el-icon>
            </div>
            <div class="info">
              <div class="value">{{ eventStats.payment || 0 }}</div>
              <div class="label">支付事件</div>
            </div>
          </div>
        </el-card>
      </el-col>
      <el-col :span="6">
        <el-card class="stat-card">
          <div class="stat-content">
            <div class="icon levelup-icon">
              <el-icon :size="24"><TrendCharts /></el-icon>
            </div>
            <div class="info">
              <div class="value">{{ eventStats.level_up || 0 }}</div>
              <div class="label">升级事件</div>
            </div>
          </div>
        </el-card>
      </el-col>
      <el-col :span="6">
        <el-card class="stat-card">
          <div class="stat-content">
            <div class="icon other-icon">
              <el-icon :size="24"><Document /></el-icon>
            </div>
            <div class="info">
              <div class="value">{{ eventStats.other || 0 }}</div>
              <div class="label">其他事件</div>
            </div>
          </div>
        </el-card>
      </el-col>
    </el-row>
    
    <el-card class="list-card">
      <template #header>
        <div class="card-header">
          <span>事件类型列表</span>
          <el-input
            v-model="searchKeyword"
            placeholder="搜索事件类型"
            style="width: 250px"
            clearable
          >
            <template #prefix>
              <el-icon><Search /></el-icon>
            </template>
          </el-input>
        </div>
      </template>
      
      <el-table :data="filteredEventTypes" style="width: 100%" border>
        <el-table-column prop="event_type" label="事件类型" min-width="150">
          <template #default="{ row }">
            <el-tag :type="getEventTagType(row.event_type)" size="large">
              {{ row.event_type }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="event_name" label="事件名称" min-width="120" />
        <el-table-column prop="description" label="描述" min-width="200" show-overflow-tooltip />
        <el-table-column label="必填字段" min-width="200">
          <template #default="{ row }">
            <div class="fields-tag">
              <el-tag
                v-for="(label, key) in row.required_fields"
                :key="key"
                type="danger"
                effect="light"
                size="small"
                style="margin-right: 4px; margin-bottom: 4px"
              >
                {{ key }}
              </el-tag>
              <span v-if="!row.required_fields || Object.keys(row.required_fields).length === 0" class="no-fields">
                无
              </span>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="可选字段" min-width="200">
          <template #default="{ row }">
            <div class="fields-tag">
              <el-tag
                v-for="(label, key) in row.optional_fields"
                :key="key"
                type="info"
                effect="light"
                size="small"
                style="margin-right: 4px; margin-bottom: 4px"
              >
                {{ key }}
              </el-tag>
              <span v-if="!row.optional_fields || Object.keys(row.optional_fields).length === 0" class="no-fields">
                无
              </span>
            </div>
          </template>
        </el-table-column>
        <el-table-column prop="is_active" label="状态" width="100" align="center">
          <template #default="{ row }">
            <el-tag :type="row.is_active ? 'success' : 'info'" size="small">
              {{ row.is_active ? '启用' : '禁用' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="180" align="center" fixed="right">
          <template #default="{ row }">
            <el-button type="primary" link @click="editEventType(row)">
              <el-icon><Edit /></el-icon>
              编辑
            </el-button>
            <el-button
              :type="row.is_active ? 'warning' : 'success'"
              link
              @click="toggleStatus(row)"
            >
              <el-icon v-if="row.is_active"><CircleClose /></el-icon>
              <el-icon v-else><CircleCheck /></el-icon>
              {{ row.is_active ? '禁用' : '启用' }}
            </el-button>
          </template>
        </el-table-column>
      </el-table>
      
      <el-pagination
        v-model:current-page="currentPage"
        v-model:page-size="pageSize"
        :page-sizes="[10, 20, 50, 100]"
        :total="eventTypes.length"
        layout="total, sizes, prev, pager, next, jumper"
        style="margin-top: 20px; justify-content: flex-end"
      />
    </el-card>
    
    <el-dialog v-model="dialogVisible" :title="dialogTitle" width="600px">
      <el-form :model="formData" :rules="formRules" ref="formRef" label-width="120px">
        <el-form-item label="事件类型" prop="event_type">
          <el-input v-model="formData.event_type" placeholder="请输入事件类型" :disabled="isEdit" />
        </el-form-item>
        <el-form-item label="事件名称" prop="event_name">
          <el-input v-model="formData.event_name" placeholder="请输入事件显示名称" />
        </el-form-item>
        <el-form-item label="描述" prop="description">
          <el-input
            v-model="formData.description"
            type="textarea"
            :rows="2"
            placeholder="请输入事件描述"
          />
        </el-form-item>
        <el-form-item label="必填字段">
          <div class="field-editor">
            <div v-for="(item, index) in formData.required_fields_list" :key="index" class="field-row">
              <el-input
                v-model="item.key"
                placeholder="字段名"
                style="width: 150px; margin-right: 8px"
              />
              <el-input
                v-model="item.label"
                placeholder="字段说明"
                style="width: 150px; margin-right: 8px"
              />
              <el-button type="danger" link @click="removeField('required', index)">
                <el-icon><Delete /></el-icon>
              </el-button>
            </div>
            <el-button type="primary" link @click="addField('required')">
              <el-icon><Plus /></el-icon>
              添加字段
            </el-button>
          </div>
        </el-form-item>
        <el-form-item label="可选字段">
          <div class="field-editor">
            <div v-for="(item, index) in formData.optional_fields_list" :key="index" class="field-row">
              <el-input
                v-model="item.key"
                placeholder="字段名"
                style="width: 150px; margin-right: 8px"
              />
              <el-input
                v-model="item.label"
                placeholder="字段说明"
                style="width: 150px; margin-right: 8px"
              />
              <el-button type="danger" link @click="removeField('optional', index)">
                <el-icon><Delete /></el-icon>
              </el-button>
            </div>
            <el-button type="primary" link @click="addField('optional')">
              <el-icon><Plus /></el-icon>
              添加字段
            </el-button>
          </div>
        </el-form-item>
        <el-form-item label="状态">
          <el-switch
            v-model="formData.is_active"
            active-text="启用"
            inactive-text="禁用"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" @click="submitForm">确定</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, computed, reactive } from 'vue'
import { ElMessage } from 'element-plus'

const searchKeyword = ref('')
const currentPage = ref(1)
const pageSize = ref(10)
const dialogVisible = ref(false)
const isEdit = ref(false)
const formRef = ref(null)

const eventStats = ref({
  login: 12580,
  payment: 2560,
  level_up: 3200,
  other: 6700
})

const eventTypes = ref([
  {
    event_type: 'login',
    event_name: '玩家登录',
    description: '玩家登录游戏事件',
    required_fields: { login_method: '登录方式', device_type: '设备类型' },
    optional_fields: { ip_region: 'IP地区' },
    is_active: true
  },
  {
    event_type: 'logout',
    event_name: '玩家登出',
    description: '玩家登出游戏事件',
    required_fields: {},
    optional_fields: { session_duration: '会话时长', reason: '登出原因' },
    is_active: true
  },
  {
    event_type: 'payment',
    event_name: '支付事件',
    description: '玩家付费事件',
    required_fields: { amount: '金额', currency: '币种', item_id: '商品ID' },
    optional_fields: { payment_method: '支付方式' },
    is_active: true
  },
  {
    event_type: 'level_up',
    event_name: '等级提升',
    description: '玩家等级提升事件',
    required_fields: { new_level: '新等级', previous_level: '旧等级' },
    optional_fields: {},
    is_active: true
  },
  {
    event_type: 'quest_complete',
    event_name: '任务完成',
    description: '玩家完成任务事件',
    required_fields: { quest_id: '任务ID', quest_name: '任务名称' },
    optional_fields: { reward: '奖励' },
    is_active: true
  },
  {
    event_type: 'item_purchase',
    event_name: '物品购买',
    description: '玩家购买商店物品事件',
    required_fields: { item_id: '物品ID', item_name: '物品名称', price: '价格' },
    optional_fields: { quantity: '数量' },
    is_active: true
  },
  {
    event_type: 'social_interaction',
    event_name: '社交互动',
    description: '玩家社交行为事件',
    required_fields: { interaction_type: '互动类型', target_player_id: '目标玩家ID' },
    optional_fields: {},
    is_active: true
  },
  {
    event_type: 'game_start',
    event_name: '游戏开始',
    description: '玩家开始一局游戏事件',
    required_fields: {},
    optional_fields: { game_mode: '游戏模式' },
    is_active: true
  },
  {
    event_type: 'game_end',
    event_name: '游戏结束',
    description: '玩家结束一局游戏事件',
    required_fields: {},
    optional_fields: { game_result: '游戏结果', duration: '游戏时长' },
    is_active: true
  }
])

const filteredEventTypes = computed(() => {
  if (!searchKeyword.value) return eventTypes.value
  const keyword = searchKeyword.value.toLowerCase()
  return eventTypes.value.filter(
    item =>
      item.event_type.toLowerCase().includes(keyword) ||
      item.event_name.toLowerCase().includes(keyword) ||
      item.description.toLowerCase().includes(keyword)
  )
})

const dialogTitle = computed(() => isEdit.value ? '编辑事件类型' : '新增事件类型')

const formData = reactive({
  event_type: '',
  event_name: '',
  description: '',
  required_fields_list: [],
  optional_fields_list: [],
  is_active: true
})

const formRules = {
  event_type: [{ required: true, message: '请输入事件类型', trigger: 'blur' }],
  event_name: [{ required: true, message: '请输入事件名称', trigger: 'blur' }]
}

const getEventTagType = (type) => {
  const typeMap = {
    login: 'success',
    logout: 'info',
    payment: 'warning',
    level_up: 'primary',
    quest_complete: 'success',
    item_purchase: 'warning',
    social_interaction: 'primary',
    game_start: 'info',
    game_end: 'info'
  }
  return typeMap[type] || 'info'
}

const openAddDialog = () => {
  isEdit.value = false
  formData.event_type = ''
  formData.event_name = ''
  formData.description = ''
  formData.required_fields_list = []
  formData.optional_fields_list = []
  formData.is_active = true
  dialogVisible.value = true
}

const editEventType = (row) => {
  isEdit.value = true
  formData.event_type = row.event_type
  formData.event_name = row.event_name
  formData.description = row.description
  formData.required_fields_list = Object.entries(row.required_fields || {}).map(([key, label]) => ({ key, label }))
  formData.optional_fields_list = Object.entries(row.optional_fields || {}).map(([key, label]) => ({ key, label }))
  formData.is_active = row.is_active
  dialogVisible.value = true
}

const addField = (type) => {
  if (type === 'required') {
    formData.required_fields_list.push({ key: '', label: '' })
  } else {
    formData.optional_fields_list.push({ key: '', label: '' })
  }
}

const removeField = (type, index) => {
  if (type === 'required') {
    formData.required_fields_list.splice(index, 1)
  } else {
    formData.optional_fields_list.splice(index, 1)
  }
}

const toggleStatus = (row) => {
  row.is_active = !row.is_active
  ElMessage.success(row.is_active ? '已启用' : '已禁用')
}

const submitForm = async () => {
  await formRef.value?.validate()
  
  const required_fields = {}
  formData.required_fields_list.forEach(item => {
    if (item.key.trim()) {
      required_fields[item.key.trim()] = item.label || item.key
    }
  })
  
  const optional_fields = {}
  formData.optional_fields_list.forEach(item => {
    if (item.key.trim()) {
      optional_fields[item.key.trim()] = item.label || item.key
    }
  })
  
  if (isEdit.value) {
    const index = eventTypes.value.findIndex(item => item.event_type === formData.event_type)
    if (index !== -1) {
      eventTypes.value[index] = {
        ...eventTypes.value[index],
        event_name: formData.event_name,
        description: formData.description,
        required_fields,
        optional_fields,
        is_active: formData.is_active
      }
    }
    ElMessage.success('编辑成功')
  } else {
    eventTypes.value.unshift({
      event_type: formData.event_type,
      event_name: formData.event_name,
      description: formData.description,
      required_fields,
      optional_fields,
      is_active: formData.is_active
    })
    ElMessage.success('新增成功')
  }
  
  dialogVisible.value = false
}
</script>

<style lang="scss" scoped>
.events-page {
  .page-header {
    display: flex;
    justify-content: space-between;
    align-items: center;
    margin-bottom: 20px;
    
    h1 {
      font-size: 24px;
      color: #1f2937;
      margin: 0;
    }
  }
  
  .stats-row {
    margin-bottom: 20px;
    
    .stat-card {
      border: none;
      border-radius: 12px;
      
      .stat-content {
        display: flex;
        align-items: center;
        
        .icon {
          width: 48px;
          height: 48px;
          border-radius: 10px;
          display: flex;
          align-items: center;
          justify-content: center;
          margin-right: 14px;
        }
        
        .login-icon {
          background: linear-gradient(135deg, #10b981 0%, #059669 100%);
          color: #fff;
        }
        
        .payment-icon {
          background: linear-gradient(135deg, #f59e0b 0%, #d97706 100%);
          color: #fff;
        }
        
        .levelup-icon {
          background: linear-gradient(135deg, #8b5cf6 0%, #7c3aed 100%);
          color: #fff;
        }
        
        .other-icon {
          background: linear-gradient(135deg, #6b7280 0%, #4b5563 100%);
          color: #fff;
        }
        
        .info {
          .value {
            font-size: 24px;
            font-weight: 700;
            color: #1f2937;
            line-height: 1.2;
          }
          
          .label {
            font-size: 13px;
            color: #6b7280;
            margin-top: 4px;
          }
        }
      }
    }
  }
  
  .list-card {
    border: none;
    border-radius: 12px;
    
    :deep(.el-card__header) {
      border-bottom: 1px solid #f3f4f6;
      
      .card-header {
        display: flex;
        justify-content: space-between;
        align-items: center;
        
        span {
          font-weight: 600;
          color: #1f2937;
          font-size: 15px;
        }
      }
    }
    
    :deep(.el-table) {
      .fields-tag {
        .no-fields {
          color: #9ca3af;
          font-style: italic;
        }
      }
    }
  }
  
  .field-editor {
    width: 100%;
    
    .field-row {
      display: flex;
      align-items: center;
      margin-bottom: 10px;
    }
  }
}
</style>
