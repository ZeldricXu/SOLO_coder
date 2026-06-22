<script setup>
import { watch } from 'vue'
import { useRoute } from 'vue-router'
import { ElLoading } from 'element-plus'

const route = useRoute()
let loadingInstance = null

watch(
  () => route.fullPath,
  () => {
    if (loadingInstance) loadingInstance.close()
    loadingInstance = ElLoading.service({
      lock: true,
      text: '加载中...',
      background: 'rgba(255, 255, 255, 0.6)'
    })
    setTimeout(() => loadingInstance?.close(), 300)
  },
  { immediate: true }
)
</script>

<template>
  <router-view />
</template>

<style scoped></style>
