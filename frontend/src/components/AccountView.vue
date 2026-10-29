<script setup lang="ts">
import type { AuthUser } from '../composables/useAuth'
defineProps<{ user: AuthUser; busy: boolean; error: string }>()
defineEmits<{ logout: [] }>()
</script>

<template>
  <main class="account-page">
    <header class="account-header"><div class="brand"><span class="brand-icon">☁</span><strong>云屿</strong><span>个人文件空间</span></div><div class="account-actions"><span class="account-name">{{ user.username }}</span><button class="logout" :disabled="busy" @click="$emit('logout')">{{ busy ? '退出中…' : '退出登录' }}</button></div></header>
    <div class="account-content"><span class="eyebrow">YOUR PERSONAL SPACE</span><h1>欢迎回来<span>.</span></h1><p class="subtitle">{{ user.username }}，你已成功登录。</p>
      <section class="empty-card"><div class="folder-icon" aria-hidden="true"><svg viewBox="0 0 56 56" fill="none"><path d="M9 18a4 4 0 0 1 4-4h10l4 4h16a4 4 0 0 1 4 4v17a5 5 0 0 1-5 5H14a5 5 0 0 1-5-5V18Z" fill="#e6eef8"/><path d="M10 23h36v16a4 4 0 0 1-4 4H14a4 4 0 0 1-4-4V23Z" fill="#b9cde5"/><path d="M21 32h14" stroke="#fff" stroke-width="2" stroke-linecap="round"/></svg></div><h2>文件服务尚未接入</h2><p>登录已连接真实账号。文件列表、上传和下载将在后续开发完成后开放。</p></section>
      <p v-if="error" class="error" role="alert">{{ error }}</p>
    </div>
  </main>
</template>

<style scoped>
.account-page{min-height:100dvh;background:#f4f7fb;color:#203b5c}.account-header{height:70px;padding:0 5%;background:#fff;border-bottom:1px solid #e9eef5;display:flex;align-items:center;justify-content:space-between;gap:16px}.brand,.account-actions{display:flex;align-items:center;gap:11px}.brand-icon{display:grid;place-items:center;width:36px;height:36px;border-radius:10px;background:#375e9b;color:#fff;font-size:20px}.brand strong{letter-spacing:.08em}.brand>span:last-child{font-size:11px;color:#9aabba;border-left:1px solid #d9e3ec;padding-left:11px}.account-name{max-width:180px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;color:#58738b;font-size:13px}.logout{border:1px solid #dce5ef;background:white;padding:9px 13px;border-radius:8px;color:#55718b;font-size:12px}.account-content{max-width:1100px;margin:auto;padding:65px 5%}.eyebrow{color:#8ca0b5;font-size:10px;font-weight:700;letter-spacing:.16em}.account-content h1{font-size:30px;margin:12px 0 9px}.account-content h1 span{color:#82a6d0}.subtitle{color:#8b9faf;font-size:14px;margin:0}.empty-card{margin-top:32px;background:#fff;border:1px solid #e8edf4;border-radius:14px;min-height:290px;padding:50px 25px;display:flex;align-items:center;justify-content:center;flex-direction:column;text-align:center;box-shadow:0 10px 30px rgba(37,62,92,.035)}.folder-icon{width:72px;height:72px;display:grid;place-items:center;border-radius:24px;background:#f4f8fc}.folder-icon svg{width:56px}.empty-card h2{font-size:17px;margin:20px 0 8px}.empty-card p{font-size:13px;line-height:1.7;color:#8e9eae;margin:0;max-width:360px}.error{font-size:13px;color:#b34f4c}@media(max-width:560px){.account-header{padding:0 18px}.brand>span:last-child{display:none}.account-content{padding:42px 18px}.account-content h1{font-size:27px}}
</style>
