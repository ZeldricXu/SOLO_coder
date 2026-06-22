import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import { authApi } from '@/api'

export const useUserStore = defineStore('user', () => {
  const token = ref(localStorage.getItem('wr_token') || '')
  const userInfo = ref(JSON.parse(localStorage.getItem('wr_user') || 'null'))

  const isLoggedIn = computed(() => !!token.value)
  const isAdmin = computed(() =>
    userInfo.value?.role === 'admin' || userInfo.value?.role === 'super_admin'
  )
  const isSuperAdmin = computed(() => userInfo.value?.role === 'super_admin')
  const isTL = computed(() => userInfo.value?.role === 'admin' || userInfo.value?.role === 'super_admin')

  async function login(username, password) {
    const res = await authApi.login(username, password)
    token.value = res.access_token
    userInfo.value = res.user
    localStorage.setItem('wr_token', res.access_token)
    localStorage.setItem('wr_user', JSON.stringify(res.user))
    return res
  }

  async function fetchMe() {
    try {
      const res = await authApi.me()
      userInfo.value = res
      localStorage.setItem('wr_user', JSON.stringify(res))
    } catch (e) { /* ignore */ }
  }

  function logout() {
    token.value = ''
    userInfo.value = null
    localStorage.removeItem('wr_token')
    localStorage.removeItem('wr_user')
  }

  return { token, userInfo, isLoggedIn, isAdmin, isSuperAdmin, isTL, login, fetchMe, logout }
})
