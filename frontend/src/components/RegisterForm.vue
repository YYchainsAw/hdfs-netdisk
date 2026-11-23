<script setup lang="ts">
import { shallowRef } from 'vue'
import { accountRequest, csrfToken, validPassword, validUsername } from '../composables/useAccountFeatures'
const emit = defineEmits<{ registered: [username: string]; cancel: [] }>()
const username = shallowRef('')
const password = shallowRef('')
const confirmation = shallowRef('')
const busy = shallowRef(false)
const error = shallowRef('')
async function submit() {
  error.value = ''
  if (!validUsername(username.value)) { error.value = '账号需为 3–64 位字母、数字、点、下划线或连字符。'; return }
  if (!validPassword(password.value)) { error.value = '密码至少 12 个字符，UTF-8 编码不超过 72 字节。'; return }
  if (password.value !== confirmation.value) { error.value = '两次输入的密码不一致。'; return }
  busy.value = true
  try {
    const token = await csrfToken()
    await accountRequest('/auth/register', { method: 'POST', headers: { 'Content-Type': 'application/json', 'X-CSRF-TOKEN': token }, body: JSON.stringify({ username: username.value, password: password.value }) })
    password.value = ''; confirmation.value = ''
    emit('registered', username.value)
  } catch (e) { error.value = e instanceof Error ? e.message : '注册失败，请稍后重试。' }
  finally { busy.value = false }
}
</script>
<template>
  <form class="register" @submit.prevent="submit">
    <label for="register-username">账号</label>
    <input id="register-username" v-model="username" autocomplete="username" maxlength="64" required :disabled="busy" />
    <label for="register-password">密码</label>
    <input id="register-password" v-model="password" type="password" autocomplete="new-password" required :disabled="busy" />
    <label for="register-confirm">确认密码</label>
    <input id="register-confirm" v-model="confirmation" type="password" autocomplete="new-password" required :disabled="busy" />
    <p class="hint">账号 3–64 位；密码至少 12 个字符，UTF-8 编码不超过 72 字节。</p>
    <p v-if="error" class="error" role="alert">{{ error }}</p>
    <button class="primary" type="submit" :disabled="busy">{{ busy ? '注册中…' : '创建账号' }}</button>
    <button type="button" class="back" :disabled="busy" @click="emit('cancel')">返回登录</button>
  </form>
</template>
<style scoped>
.register{display:flex;flex-direction:column;gap:8px;margin-top:22px}.register label{font-size:12px;color:#56708a;font-weight:650;margin-top:5px}.register input{height:44px;border:1px solid #dbe5ef;border-radius:9px;background:#fbfcfe;padding:0 13px;font:inherit;font-size:14px;color:#243c55}.hint,.error{font-size:12px;line-height:1.55;margin:3px 0;color:#748ba3}.error{color:#b34f4c}.primary,.back{height:44px;border-radius:9px;cursor:pointer;font:inherit;font-size:14px}.primary{border:0;background:#315d9b;color:white;margin-top:8px}.back{border:1px solid #dbe5ef;background:white;color:#547392}
</style>
