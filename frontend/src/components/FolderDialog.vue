<script setup lang="ts">
import { onMounted, shallowRef, useTemplateRef } from 'vue'
defineProps<{ busy: boolean; error: string }>()
const emit = defineEmits<{ cancel: []; create: [name: string] }>()
const dialog = useTemplateRef<HTMLDialogElement>('dialog')
const name = shallowRef('')
onMounted(() => dialog.value?.showModal())
</script>
<template><dialog ref="dialog" aria-labelledby="folder-title" class="dialog" @cancel.prevent="!busy && emit('cancel')"><form @submit.prevent="emit('create', name)"><div class="icon" aria-hidden="true">▰</div><h2 id="folder-title">新建文件夹</h2><p>为文件夹取一个名字。</p><label for="folder-name">文件夹名称</label><input id="folder-name" v-model="name" autocomplete="off" autofocus :disabled="busy" :aria-invalid="!!error" :aria-describedby="error ? 'folder-error' : undefined" placeholder="例如：项目资料"/><p v-if="error" id="folder-error" class="error" role="alert">{{ error }}</p><div class="actions"><button type="button" class="cancel" :disabled="busy" @click="emit('cancel')">取消</button><button class="confirm" type="submit" :disabled="busy">{{ busy ? '创建中…' : '创建文件夹' }}</button></div></form></dialog></template>
<style scoped>
.dialog{width:min(440px,calc(100vw - 32px));max-width:none;padding:25px;border:1px solid #dfe6ef;border-radius:20px;color:#20344d;background:#fff;box-shadow:0 30px 80px #14253d38}.dialog::backdrop{background:#1321347a}.icon{display:grid;place-items:center;width:43px;height:43px;color:#d5a044;background:#fdf4e3;border-radius:13px}h2{margin:20px 0 8px;font-size:21px}p{color:#6d7d90;font-size:14px;margin:0 0 21px;line-height:1.6}label{display:block;margin-bottom:8px;font-size:13px;font-weight:650}input{width:100%;height:44px;padding:0 12px;border:1px solid #d9e2ed;border-radius:10px;background:#fbfcfe;color:#20344d}p.error{margin:9px 0 0;color:#b64646;font-size:13px}.actions{display:flex;justify-content:flex-end;gap:9px;margin-top:26px}.actions button{min-height:40px;padding:0 16px;border-radius:10px;font-size:13px;font-weight:650}.cancel{border:1px solid #e6ebf1;background:#f3f6f9;color:#53677c}.confirm{border:1px solid #4669c7;background:#4669c7;color:#fff}
</style>
