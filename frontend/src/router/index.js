import { createRouter, createWebHashHistory } from 'vue-router'
import { useUserStore } from '@/store/user'

const routes = [
  {
    path: '/login',
    name: 'Login',
    component: () => import('@/views/Login.vue'),
    meta: { title: '登录', public: true }
  },
  {
    path: '/',
    component: () => import('@/layouts/MainLayout.vue'),
    redirect: '/dashboard',
    children: [
      {
        path: 'dashboard',
        name: 'Dashboard',
        component: () => import('@/views/Dashboard.vue'),
        meta: { title: '工作台', icon: 'DataAnalysis' }
      },
      {
        path: 'report/write',
        name: 'ReportWrite',
        component: () => import('@/views/report/ReportWrite.vue'),
        meta: { title: '填写周报', icon: 'Edit' }
      },
      {
        path: 'report/list',
        name: 'ReportList',
        component: () => import('@/views/report/ReportList.vue'),
        meta: { title: '周报列表', icon: 'Tickets' }
      },
      {
        path: 'summary',
        name: 'Summary',
        component: () => import('@/views/summary/SummaryView.vue'),
        meta: { title: '汇总查看', icon: 'CollectionTag' }
      },
      {
        path: 'statistics',
        name: 'Statistics',
        component: () => import('@/views/statistics/Statistics.vue'),
        meta: { title: '统计面板', icon: 'TrendCharts' }
      },
      {
        path: 'templates',
        name: 'Templates',
        component: () => import('@/views/admin/TemplateManage.vue'),
        meta: { title: '模板管理', icon: 'Document', admin: true }
      },
      {
        path: 'teams',
        name: 'Teams',
        component: () => import('@/views/admin/TeamManage.vue'),
        meta: { title: '团队管理', icon: 'UserFilled', admin: true }
      },
      {
        path: 'users',
        name: 'Users',
        component: () => import('@/views/admin/UserManage.vue'),
        meta: { title: '用户管理', icon: 'Avatar', admin: true }
      },
      {
        path: 'export',
        name: 'Export',
        component: () => import('@/views/admin/ExportDistribute.vue'),
        meta: { title: '导出分发', icon: 'Promotion', admin: true }
      }
    ]
  },
  { path: '/:pathMatch(.*)*', redirect: '/dashboard' }
]

const router = createRouter({
  history: createWebHashHistory(),
  routes
})

router.beforeEach((to, from, next) => {
  document.title = `${to.meta.title || ''} - 周报自动汇总系统`
  const userStore = useUserStore()

  if (to.meta.public) {
    next()
    return
  }

  if (!userStore.isLoggedIn) {
    next({ path: '/login', query: { redirect: to.fullPath } })
    return
  }

  if (to.meta.admin && !userStore.isAdmin) {
    next('/dashboard')
    return
  }

  next()
})

export default router
