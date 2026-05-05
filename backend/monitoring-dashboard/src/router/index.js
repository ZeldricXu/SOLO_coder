import { createRouter, createWebHistory } from 'vue-router'

const routes = [
  {
    path: '/',
    name: 'Dashboard',
    component: () => import('@/views/Dashboard.vue'),
    meta: { title: '实时监控' }
  },
  {
    path: '/retention',
    name: 'Retention',
    component: () => import('@/views/Retention.vue'),
    meta: { title: '留存分析' }
  },
  {
    path: '/funnel',
    name: 'Funnel',
    component: () => import('@/views/Funnel.vue'),
    meta: { title: '漏斗分析' }
  },
  {
    path: '/profile',
    name: 'Profile',
    component: () => import('@/views/Profile.vue'),
    meta: { title: '玩家画像' }
  },
  {
    path: '/events',
    name: 'Events',
    component: () => import('@/views/Events.vue'),
    meta: { title: '事件管理' }
  }
]

const router = createRouter({
  history: createWebHistory(),
  routes
})

router.beforeEach((to, from, next) => {
  document.title = `${to.meta.title || 'GameStats'} - 游戏数据分析平台`
  next()
})

export default router
