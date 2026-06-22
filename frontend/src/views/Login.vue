<script setup>
import { ref } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import { useUserStore } from '@/store/user'
import { authApi } from '@/api'

const router = useRouter()
const route = useRoute()
const userStore = useUserStore()

const loading = ref(false)
const form = ref({ username: 'admin', password: 'admin123' })

async function handleLogin() {
  if (!form.value.username || !form.value.password) {
    ElMessage.warning('请输入用户名和密码')
    return
  }
  loading.value = true
  try {
    await userStore.login(form.value.username, form.value.password)
    ElMessage.success('登录成功')
    const redirect = route.query.redirect || '/dashboard'
    router.push(redirect)
  } catch (e) {
    // handled by interceptor
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div style="min-height:100vh;display:flex;align-items:center;justify-content:center;background:linear-gradient(135deg,#667eea 0%,#764ba2 100%);">
    <div style="width:420px;background:#fff;border-radius:16px;padding:40px 36px;box-shadow:0 20px 60px rgba(0,0,0,0.2);">
      <div style="text-align:center;margin-bottom:30px;">
        <div style="font-size:56px;">📝</div>
        <h1 style="font-size:24px;color:#1f2937;margin:12px 0 6px;">周报自动汇总系统</h1>
        <p style="color:#909399;font-size:14px;">高效收集 · 智能汇总 · 数据驱动</p>
      </div>
      <el-form :model="form" size="large" @submit.prevent="handleLogin">
        <el-form-item>
          <el-input v-model="form.username" placeholder="用户名" :prefix-icon="'User'" />
        </el-form-item>
        <el-form-item>
          <el-input v-model="form.password" type="password" placeholder="密码" :prefix-icon="'Lock'" show-password @keyup.enter="handleLogin" />
        </el-form-item>
        <el-button type="primary" size="large" style="width:100%;" :loading="loading" @click="handleLogin">登 录</el-button>
      </el-form>
      <div style="margin-top:20px;padding:12px;background:#f5f7fa;border-radius:8px;font-size:12px;color:#606266;">
        <div>💡 默认账号：admin / admin123（超级管理员）</div>
        <div>普通用户：zhangsan ~ zhoujiu / 123456</div>
      </div>
    </div>
  </div>
</template>
