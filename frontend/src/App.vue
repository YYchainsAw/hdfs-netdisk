<script setup lang="ts">
import { shallowRef } from 'vue'
import LoginView from './components/LoginView.vue'
import DriveView from './components/DriveView.vue'
import AccountView from './components/AccountView.vue'
import { useAuth } from './composables/useAuth'
const demo = shallowRef(false)
const { user, busy, checking, error, login, logout, expire } = useAuth()
</script>

<template>
  <DriveView v-if="demo" @exit="demo = false" />
  <AccountView v-else-if="user" :user="user" :busy="busy" :error="error" @logout="logout" @expired="expire" />
  <LoginView v-else :busy="busy || checking" :error="error" @enter="demo = true" @login="login" />
</template>
