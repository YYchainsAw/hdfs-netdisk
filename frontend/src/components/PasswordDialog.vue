<script setup lang="ts">
import { onMounted, shallowRef, useTemplateRef } from 'vue'
import { AccountError, accountRequest, csrfToken, validPassword } from '../composables/useAccountFeatures'
const emit = defineEmits<{ cancel: []; changed: []; expired: [] }>()
const dialog = useTemplateRef<HTMLDialogElement>('dialog')
const oldPassword = shallowRef(''), newPassword = shallowRef(''), confirm = shallowRef('')
const busy = shallowRef(false), error = shallowRef('')
onMounted(() => dialog.value?.showModal())
function close() { if (!busy.value) emit('cancel') }
async function submit() {
  error.value = ''
  if (!validPassword(newPassword.value)) { error.value = '新密码至少 12 个字符，UTF-8 编码不超过 72 字节。'; return }
  if (newPassword.value !== confirm.value) { error.value = '两次输入的新密码不一致。'; return }
  busy.value = true
  try {
    const token = await csrfToken()
    await accountRequest('/auth/password', { method: 'POST', headers: { 'Content-Type': 'application/json', 'X-CSRF-TOKEN': token }, body: JSON.stringify({ oldPassword: oldPassword.value, newPassword: newPassword.value }) })
    oldPassword.value = ''; newPassword.value = ''; confirm.value = ''
    emit('changed')
  } catch (e) {
    if (e instanceof AccountError && (e.status === 401 || e.code === 'PASSWORD_CHANGED')) emit('expired')
    else error.value = e instanceof Error ? e.message : '修改失败，请稍后重试。'
  } finally { busy.value = false }
}
</script>
<template>
  <dialog ref="dialog" class="dialog" @cancel.prevent="close">
    <form class="form" @submit.prevent="submit">
      <h2>修改密码</h2><p class="hint">修改成功后需要重新登录。</p>
      <label for="old-password">当前密码</label><input id="old-password" v-model="oldPassword" type="password" autocomplete="current-password" required :disabled="busy" />
      <label for="new-password">新密码</label><input id="new-password" v-model="newPassword" type="password" autocomplete="new-password" required :disabled="busy" />
      <label for="confirm-password">确认新密码</label><input id="confirm-password" v-model="confirm" type="password" autocomplete="new-password" required :disabled="busy" />
      <p class="hint">新密码至少 12 个字符，UTF-8 编码不超过 72 字节。</p>
      <p v-if="error" class="error" role="alert">{{ error }}</p>
      <div class="actions"><button type="button" :disabled="busy" @click="close">取消</button><button class="primary" type="submit" :disabled="busy">{{ busy ? '提交中…' : '确认修改' }}</button></div>
    </form>
  </dialog>
</template>
<style scoped>
.dialog{width:min(430px,calc(100vw - 32px));border:0;border-radius:16px;box-shadow:0 20px 70px #1e344950;padding:26px;color:#263e58}.dialog::backdrop{background:#12243b99}.form{display:flex;flex-direction:column;gap:9px}.form h2{font-size:19px;margin:0}.form label{font-size:13px;color:#50677e;margin-top:6px}.form input{height:42px;border:1px solid #dce5ee;border-radius:8px;padding:0 11px;font:inherit}.hint,.error{margin:0;color:#78899b;font-size:12px;line-height:1.5}.error{color:#b34f4c}.actions{display:flex;justify-content:flex-end;gap:10px;margin-top:12px}.actions button{border:1px solid #dce5ef;background:white;color:#476079;border-radius:8px;padding:10px 15px}.actions .primary{background:#315d9b;border-color:#315d9b;color:white}
</style>
