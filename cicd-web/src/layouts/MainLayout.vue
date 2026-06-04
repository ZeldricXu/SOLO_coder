<template>
  <el-container class="layout-container">
    <el-aside width="240px" class="sidebar">
      <div class="logo">
        <el-icon size="32" color="#409eff"><Operation /></el-icon>
        <span class="logo-text">CI/CD 平台</span>
      </div>
      <el-menu
        :default-active="activeMenu"
        router
        class="menu"
        background-color="#001529"
        text-color="#fff"
        active-text-color="#409eff"
      >
        <template v-for="route in menuRoutes" :key="route.path">
          <el-menu-item :index="`/${route.path}`" v-if="!route.meta.hidden">
            <el-icon><component :is="route.meta.icon" /></el-icon>
            <span>{{ route.meta.title }}</span>
          </el-menu-item>
        </template>
      </el-menu>
    </el-aside>

    <el-container>
      <el-header class="header">
        <div class="header-left">
          <el-breadcrumb separator="/">
            <el-breadcrumb-item :to="{ path: '/dashboard' }">首页</el-breadcrumb-item>
            <el-breadcrumb-item v-if="$route.meta.title">{{ $route.meta.title }}</el-breadcrumb-item>
          </el-breadcrumb>
        </div>
        <div class="header-right">
          <el-select
            v-model="selectedProject"
            size="default"
            style="width: 180px; margin-right: 20px"
            @change="onProjectChange"
          >
            <el-option label="默认项目" :value="{ id: 1, name: '默认项目' }" />
          </el-select>
          <el-dropdown @command="handleCommand">
            <span class="user-info">
              <el-avatar :size="32" style="vertical-align: middle">
                {{ userStore.userInfo?.username?.charAt(0)?.toUpperCase() }}
              </el-avatar>
              <span style="margin-left: 8px">{{ userStore.userInfo?.username || '用户' }}</span>
              <el-icon><ArrowDown /></el-icon>
            </span>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item command="profile">个人中心</el-dropdown-item>
                <el-dropdown-item command="settings">系统设置</el-dropdown-item>
                <el-dropdown-item divided command="logout">退出登录</el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
        </div>
      </el-header>

      <el-main class="main">
        <router-view />
      </el-main>
    </el-container>
  </el-container>
</template>

<script setup>
import { ref, computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useUserStore } from '@/store/user'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Operation, ArrowDown, DataBoard, Box, Monitor, Check, Cpu, Folder, Setting, Upload } from '@element-plus/icons-vue'

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()

const selectedProject = ref(userStore.currentProject)

const activeMenu = computed(() => {
  const path = route.path
  if (path.startsWith('/pipelines')) return '/pipelines'
  if (path.startsWith('/deployments')) return '/deployments'
  if (path.startsWith('/artifacts')) return '/artifacts'
  if (path.startsWith('/environments')) return '/environments'
  if (path.startsWith('/approvals')) return '/approvals'
  if (path.startsWith('/runners')) return '/runners'
  if (path.startsWith('/projects')) return '/projects'
  if (path.startsWith('/settings')) return '/settings'
  return '/dashboard'
})

const menuRoutes = computed(() => {
  return router.options.routes
    .find(r => r.path === '/')
    ?.children
    ?.filter(r => !r.meta?.hidden)
    ?.filter(r => {
      if (r.meta?.roles && r.meta.roles.length > 0) {
        return userStore.hasAnyRole(r.meta.roles)
      }
      return true
    }) || []
})

const onProjectChange = (project) => {
  userStore.setCurrentProject(project)
}

const handleCommand = (command) => {
  if (command === 'logout') {
    ElMessageBox.confirm('确定要退出登录吗？', '提示', {
      confirmButtonText: '确定',
      cancelButtonText: '取消',
      type: 'warning'
    }).then(() => {
      userStore.logout()
      router.push('/login')
      ElMessage.success('已退出登录')
    }).catch(() => {})
  } else if (command === 'settings') {
    router.push('/settings')
  }
}
</script>

<style scoped lang="scss">
.layout-container {
  height: 100vh;
}

.sidebar {
  background-color: #001529;
  overflow: hidden;

  .logo {
    height: 64px;
    display: flex;
    align-items: center;
    justify-content: center;
    color: #fff;
    border-bottom: 1px solid #1f3a5c;

    .logo-text {
      font-size: 18px;
      font-weight: 600;
      margin-left: 12px;
    }
  }

  .menu {
    border-right: none;
  }
}

.header {
  background-color: #fff;
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 24px;
  box-shadow: 0 1px 4px rgba(0, 21, 41, 0.08);

  .header-right {
    display: flex;
    align-items: center;

    .user-info {
      display: flex;
      align-items: center;
      cursor: pointer;
    }
  }
}

.main {
  background-color: #f5f7fa;
  overflow-y: auto;
}
</style>
