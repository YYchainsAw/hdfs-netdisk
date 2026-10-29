<script setup lang="ts">
import { shallowRef } from 'vue'
import LoginView from './components/LoginView.vue'
import DriveView from './components/DriveView.vue'
const entered = shallowRef(false)
</script>

<template>
  <DriveView v-if="entered" @exit="entered = false" />
  <LoginView v-else @enter="entered = true" />
</template>
