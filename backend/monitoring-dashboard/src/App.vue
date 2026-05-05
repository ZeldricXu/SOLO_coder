<template>
  <div class="app-container">
    <el-container>
      <el-aside width="220px" class="sidebar">
        <div class="logo">
          <el-icon :size="28"><TrendCharts /></el-icon>
          <span>GameStats</span>
        </div>
        <el-menu
          :default-active="activeMenu"
          router
          background-color="#1f2937"
          text-color="#9ca3af"
          active-text-color="#3b82f6"
        >
          <el-menu-item index="/">
            <el-icon><DataLine /></el-icon>
            <span>实时监控</span>
          </el-menu-item>
          <el-menu-item index="/retention">
            <el-icon><User /></el-icon>
            <span>留存分析</span>
          </el-menu-item>
          <el-menu-item index="/funnel">
            <el-icon><Share /></el-icon>
            <span>漏斗分析</span>
          </el-menu-item>
          <el-menu-item index="/profile">
            <el-icon><Avatar /></el-icon>
            <span>玩家画像</span>
          </el-menu-item>
          <el-menu-item index="/events">
            <el-icon><Document /></el-icon>
            <span>事件管理</span>
          </el-menu-item>
        </el-menu>
      </el-aside>
      <el-container>
        <el-header class="header">
          <div class="header-left">
            <el-select v-model="currentGameId" placeholder="选择游戏" style="width: 200px">
              <el-option label="默认游戏" value="game_mmorpg_01" />
              <el-option label="测试游戏" value="test_game_01" />
            </el-select>
          </div>
          <div class="header-right">
            <el-dropdown>
              <div class="user-info">
                <el-avatar :size="32" icon="UserFilled" />
                <span class="username">管理员</span>
                <el-icon class="dropdown-icon"><ArrowDown /></el-icon>
              </div>
              <template #dropdown>
                <el-dropdown-menu>
                  <el-dropdown-item>个人设置</el-dropdown-item>
                  <el-dropdown-item divided>退出登录</el-dropdown-item>
                </el-dropdown-menu>
              </template>
            </el-dropdown>
          </div>
        </el-header>
        <el-main class="main-content">
          <router-view v-slot="{ Component }">
            <transition name="fade" mode="out-in">
              <component :is="Component" />
            </transition>
          </router-view>
        </el-main>
      </el-container>
    </el-container>
  </div>
</template>

<script setup>
import { ref, computed } from 'vue'
import { useRoute } from 'vue-router'

const route = useRoute()
const currentGameId = ref('game_mmorpg_01')

const activeMenu = computed(() => {
  return route.path
})
</script>

<style lang="scss">
* {
  margin: 0;
  padding: 0;
  box-sizing: border-box;
}

html, body, #app {
  height: 100%;
  width: 100%;
}

.app-container {
  height: 100%;
}

.el-container {
  height: 100%;
}

.sidebar {
  background-color: #1f2937;
  border-right: 1px solid #374151;
  
  .logo {
    display: flex;
    align-items: center;
    justify-content: center;
    height: 60px;
    color: #3b82f6;
    font-size: 20px;
    font-weight: bold;
    padding: 0 20px;
    border-bottom: 1px solid #374151;
    
    span {
      margin-left: 10px;
    }
  }
  
  .el-menu {
    border-right: none;
  }
}

.header {
  background-color: #fff;
  border-bottom: 1px solid #e5e7eb;
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 24px;
  
  .header-left {
    display: flex;
    align-items: center;
  }
  
  .header-right {
    .user-info {
      display: flex;
      align-items: center;
      cursor: pointer;
      
      .username {
        margin: 0 8px;
        color: #374151;
      }
      
      .dropdown-icon {
        font-size: 12px;
        color: #9ca3af;
      }
    }
  }
}

.main-content {
  background-color: #f3f4f6;
  padding: 24px;
  overflow-y: auto;
}

.fade-enter-active,
.fade-leave-active {
  transition: opacity 0.2s ease;
}

.fade-enter-from,
.fade-leave-to {
  opacity: 0;
}
</style>
