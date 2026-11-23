<script setup lang="ts">
import { shallowRef } from 'vue'
import LoginView from './components/LoginView.vue'
import DriveView from './components/DriveView.vue'
import AccountView from './components/AccountView.vue'
import ShareOpen from './components/ShareOpen.vue'
import { useAuth } from './composables/useAuth'
const shareMatch = location.pathname.match(/^\/s\/([A-Za-z0-9_-]{43})\/?$/)
const demo = shallowRef(false)
const { user, busy, checking, error, login, logout, expire } = useAuth()
</script>

<template>
  <ShareOpen v-if="shareMatch" :token="shareMatch[1]!" />
  <DriveView v-else-if="demo" @exit="demo = false" />
  <AccountView v-else-if="user" :user="user" :busy="busy" :error="error" @logout="logout" @expired="expire()" @password-changed="expire('密码已修改，请重新登录。')" />
  <LoginView v-else :busy="busy || checking" :error="error" @enter="demo = true" @login="login" />
</template>
