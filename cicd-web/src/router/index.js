import { createRouter, createWebHistory } from 'vue-router'
import { useUserStore } from '@/store/user'

const routes = [
  {
    path: '/login',
    name: 'Login',
    component: () => import('@/views/Login.vue'),
    meta: { requiresAuth: false }
  },
  {
    path: '/',
    component: () => import('@/layouts/MainLayout.vue'),
    meta: { requiresAuth: true },
    redirect: '/dashboard',
    children: [
      {
        path: 'dashboard',
        name: 'Dashboard',
        component: () => import('@/views/Dashboard.vue'),
        meta: { title: '仪表盘', icon: 'DataBoard' }
      },
      {
        path: 'pipelines',
        name: 'PipelineList',
        component: () => import('@/views/PipelineList.vue'),
        meta: { title: '流水线', icon: 'Operation' }
      },
      {
        path: 'pipelines/:id',
        name: 'PipelineDetail',
        component: () => import('@/views/PipelineDetail.vue'),
        meta: { title: '流水线详情', hidden: true }
      },
      {
        path: 'pipelines/:pipelineId/executions/:executionId',
        name: 'ExecutionDetail',
        component: () => import('@/views/ExecutionDetail.vue'),
        meta: { title: '执行详情', hidden: true }
      },
      {
        path: 'deployments',
        name: 'DeploymentList',
        component: () => import('@/views/DeploymentList.vue'),
        meta: { title: '部署历史', icon: 'Upload' }
      },
      {
        path: 'artifacts',
        name: 'ArtifactList',
        component: () => import('@/views/ArtifactList.vue'),
        meta: { title: '制品管理', icon: 'Box' }
      },
      {
        path: 'environments',
        name: 'EnvironmentList',
        component: () => import('@/views/EnvironmentList.vue'),
        meta: { title: '环境管理', icon: 'Monitor' }
      },
      {
        path: 'approvals',
        name: 'ApprovalList',
        component: () => import('@/views/ApprovalList.vue'),
        meta: { title: '审批中心', icon: 'Check' }
      },
      {
        path: 'runners',
        name: 'RunnerList',
        component: () => import('@/views/RunnerList.vue'),
        meta: { title: 'Runner管理', icon: 'Cpu' }
      },
      {
        path: 'projects',
        name: 'ProjectList',
        component: () => import('@/views/ProjectList.vue'),
        meta: { title: '项目管理', icon: 'Folder', roles: ['PLATFORM_ADMIN', 'PROJECT_OWNER'] }
      },
      {
        path: 'settings',
        name: 'Settings',
        component: () => import('@/views/Settings.vue'),
        meta: { title: '系统设置', icon: 'Setting', roles: ['PLATFORM_ADMIN'] }
      }
    ]
  }
]

const router = createRouter({
  history: createWebHistory(),
  routes
})

router.beforeEach((to, from, next) => {
  const userStore = useUserStore()
  const isAuthenticated = userStore.token

  if (to.meta.requiresAuth && !isAuthenticated) {
    next('/login')
  } else if (to.path === '/login' && isAuthenticated) {
    next('/')
  } else {
    if (to.meta.roles && to.meta.roles.length > 0) {
      const hasRole = to.meta.roles.some(role => userStore.roles?.includes(role))
      if (!hasRole) {
        next('/403')
        return
      }
    }
    next()
  }
})

export default router
