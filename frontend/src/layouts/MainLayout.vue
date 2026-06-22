<script setup>
import { ref, computed, onMounted } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { useUserStore } from '@/store/user'
import { ElMessageBox, ElMessage } from 'element-plus'

const router = useRouter()
const route = useRoute()
const userStore = useUserStore()
const collapse = ref(false)

const menuItems = computed(() => {
  const base = [
    { path: '/dashboard', title: '工作台', icon: 'DataAnalysis' },
    { path: '/report/write', title: '填写周报', icon: 'Edit' },
    { path: '/report/list', title: '周报列表', icon: 'Tickets' },
    { path: '/summary', title: '汇总查看', icon: 'CollectionTag' },
    { path: '/statistics', title: '统计面板', icon: 'TrendCharts' }
  ]
  if (userStore.isAdmin) {
    base.push(
      { path: '/templates', title: '模板管理', icon: 'Document' },
      { path: '/teams', title: '团队管理', icon: 'UserFilled' },
      { path: '/users', title: '用户管理', icon: 'Avatar' },
      { path: '/export', title: '导出分发', icon: 'Promotion' }
    )
  }
  return base
})

function handleLogout() {
  ElMessageBox.confirm('确定要退出登录吗？', '提示', {
    type: 'warning', confirmButtonText: '退出', cancelButtonText: '取消'
  }).then(() => {
    userStore.logout()
    router.push('/login')
    ElMessage.success('已退出')
  }).catch(() => {})
}

onMounted(() => {
  if (userStore.token && !userStore.userInfo) {
    userStore.fetchMe()
  }
})
</script>

<template>
  <el-container style="min-height:100vh;">
    <el-aside :width="collapse ? '64px' : '220px'" style="background:#1f2937;transition:width .3s;">
      <div style="height:60px;display:flex;align-items:center;justify-content:center;color:#fff;font-size:16px;font-weight:600;border-bottom:1px solid #374151;">
        <span style="font-size:24px;margin-right:8px;" v-if="!collapse">📝</span>
        <span v-if="!collapse">周报系统</span>
        <span v-else>📝</span>
      </div>
      <el-menu
        :default-active="route.path"
        :collapse="collapse"
        background-color="#1f2937"
        text-color="#d1d5db"
        active-text-color="#60a5fa"
        router
        style="border-right:none;"
      >
        <el-menu-item v-for="m in menuItems" :key="m.path" :index="m.path">
          <el-icon><component :is="m.icon" /></el-icon>
          <template #title>{{ m.title }}</template>
        </el-menu-item>
      </el-menu>
    </el-aside>

    <el-container>
      <el-header style="background:#fff;border-bottom:1px solid #e5e7eb;display:flex;align-items:center;justify-content:space-between;padding:0 20px;">
        <div class="flex-center" style="gap:16px;">
          <el-icon style="cursor:pointer;font-size:18px;" @click="collapse = !collapse">
            <Fold v-if="!collapse" /><Expand v-else />
          </el-icon>
          <el-breadcrumb separator="/">
            <el-breadcrumb-item :to="{ path: '/dashboard' }">首页</el-breadcrumb-item>
            <el-breadcrumb-item v-for="m in menuItems.filter(x=>x.path===route.path)" :key="m.path">{{ m.title }}</el-breadcrumb-item>
          </el-breadcrumb>
        </div>
        <div class="flex-center" style="gap:12px;">
          <el-tag size="small" :type="userStore.userInfo?.role === 'super_admin' ? 'danger' : (userStore.userInfo?.role === 'admin' ? 'warning' : 'info')">
            {{ userStore.userInfo?.role === 'super_admin' ? '超级管理员' : (userStore.userInfo?.role === 'admin' ? '管理员' : '成员') }}
          </el-tag>
          <el-dropdown>
            <div class="flex-center" style="cursor:pointer;gap:8px;">
              <el-avatar :size="32" style="background:#409eff;">{{ userStore.userInfo?.full_name?.charAt(0) }}</el-avatar>
              <span style="color:#374151;">{{ userStore.userInfo?.full_name || userStore.userInfo?.username }}</span>
              <el-icon><ArrowDown /></el-icon>
            </div>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item disabled>{{ userStore.userInfo?.email }}</el-dropdown-item>
                <el-dropdown-item disabled>{{ userStore.userInfo?.team_name || '未分配团队' }}</el-dropdown-item>
                <el-dropdown-item divided @click="handleLogout"><el-icon><SwitchButton /></el-icon> 退出登录</el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
        </div>
      </el-header>

      <el-main style="padding:16px;background:#f5f7fa;">
        <router-view v-slot="{ Component }">
          <transition name="fade" mode="out-in">
            <component :is="Component" />
          </transition>
        </router-view>
      </el-main>
    </el-container>
  </el-container>
</template>

<style scoped>
.fade-enter-active, .fade-leave-active { transition: opacity .2s ease; }
.fade-enter-from, .fade-leave-to { opacity: 0; }
:deep(.el-menu-item) { height: 48px; line-height: 48px; }
</style>
