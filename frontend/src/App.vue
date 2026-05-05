<template>
  <div id="app">
    <el-container>
      <el-aside width="200px" class="sidebar">
        <div class="logo">
          <i class="el-icon-folder-opened"></i>
          <span>MediaHub</span>
        </div>
        <el-menu
          :default-active="activeMenu"
          background-color="#304156"
          text-color="#bfcbd9"
          active-text-color="#409EFF"
          router
        >
          <el-menu-item index="/upload">
            <i class="el-icon-upload"></i>
            <span slot="title">媒体上传</span>
          </el-menu-item>
          <el-menu-item index="/media">
            <i class="el-icon-folder"></i>
            <span slot="title">媒体库</span>
          </el-menu-item>
          <el-menu-item index="/review">
            <i class="el-icon-edit-outline"></i>
            <span slot="title">内容审核</span>
            <el-badge :value="pendingReviewCount" :hidden="pendingReviewCount === 0" class="menu-badge">
            </el-badge>
          </el-menu-item>
          <el-menu-item index="/distribution">
            <i class="el-icon-share"></i>
            <span slot="title">分发管理</span>
          </el-menu-item>
        </el-menu>
      </el-aside>
      <el-container>
        <el-header class="header">
          <div class="breadcrumb">
            <el-breadcrumb separator="/">
              <el-breadcrumb-item :to="{ path: '/' }">首页</el-breadcrumb-item>
              <el-breadcrumb-item>{{ currentPageTitle }}</el-breadcrumb-item>
            </el-breadcrumb>
          </div>
          <div class="user-info">
            <el-dropdown>
              <span class="el-dropdown-link">
                <i class="el-icon-user-solid"></i>
                管理员
                <i class="el-icon-arrow-down el-icon--right"></i>
              </span>
              <el-dropdown-menu slot="dropdown">
                <el-dropdown-item>个人设置</el-dropdown-item>
                <el-dropdown-item divided>退出登录</el-dropdown-item>
              </el-dropdown-menu>
            </el-dropdown>
          </div>
        </el-header>
        <el-main class="main-content">
          <router-view />
        </el-main>
      </el-container>
    </el-container>
  </div>
</template>

<script>
import { mapGetters } from 'vuex'

export default {
  name: 'App',
  computed: {
    ...mapGetters(['pendingReviewCount']),
    activeMenu() {
      return this.$route.path
    },
    currentPageTitle() {
      const titleMap = {
        '/upload': '媒体上传',
        '/media': '媒体库',
        '/review': '内容审核',
        '/distribution': '分发管理'
      }
      return titleMap[this.$route.path] || '首页'
    }
  },
  mounted() {
    this.$store.dispatch('fetchReviewStats')
  }
}
</script>

<style lang="scss">
#app {
  width: 100%;
  height: 100vh;
  font-family: 'Helvetica Neue', Helvetica, 'PingFang SC', 'Hiragino Sans GB',
    'Microsoft YaHei', '微软雅黑', Arial, sans-serif;
}

.sidebar {
  background-color: #304156;
  height: 100vh;
  position: fixed;
  left: 0;
  top: 0;
  bottom: 0;
  z-index: 1000;

  .logo {
    height: 60px;
    display: flex;
    align-items: center;
    justify-content: center;
    background-color: #2b3a4a;
    color: #fff;
    font-size: 20px;
    font-weight: bold;

    i {
      margin-right: 8px;
      font-size: 24px;
    }
  }

  .el-menu {
    border-right: none;
  }

  .menu-badge {
    position: absolute;
    top: 8px;
    right: 20px;
  }
}

.header {
  background-color: #fff;
  box-shadow: 0 1px 4px rgba(0, 21, 41, 0.08);
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 20px;
  margin-left: 200px;

  .breadcrumb {
    font-size: 14px;
  }

  .user-info {
    .el-dropdown-link {
      cursor: pointer;
      color: #409EFF;
      display: flex;
      align-items: center;

      i {
        margin-right: 5px;
      }
    }
  }
}

.main-content {
  margin-left: 200px;
  background-color: #f0f2f5;
  min-height: calc(100vh - 60px);
  padding: 20px;
}

.el-card {
  margin-bottom: 20px;
}

.table-actions {
  button {
    margin-right: 5px;
  }
}
</style>
