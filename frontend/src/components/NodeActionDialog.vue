<script setup lang="ts">
import { onMounted, shallowRef, useTemplateRef } from 'vue'
import type { NodeItem } from '../composables/useNodes'
const props = defineProps<{ node: NodeItem; mode: 'rename' | 'delete'; busy: boolean; error: string }>()
const emit = defineEmits<{ cancel: []; submit: [name?: string] }>()
const dialog = useTemplateRef<HTMLDialogElement>('dialog')
const name = shallowRef(props.node.name)
onMounted(() => dialog.value?.showModal())
function close() { if (!props.busy) emit('cancel') }
</script>
<template>
  <dialog ref="dialog" class="dialog" @cancel.prevent="close">
    <form class="form" @submit.prevent="emit('submit', mode === 'rename' ? name : undefined)">
      <h2>{{ mode === 'rename' ? '重命名' : '删除确认' }}</h2>
      <template v-if="mode === 'rename'"><label for="node-name">新名称</label><input id="node-name" v-model="name" required maxlength="382" :disabled="busy" /></template>
      <p v-else class="detail">将“{{ node.name }}”移入回收站吗？{{ node.type === 'FOLDER' ? '文件夹内的内容也会一起移入。' : '' }}单次最多处理 1000 个项目；包含正在进行的上传任务、已单独删除的项目或正在清理的项目时，暂不能删除。</p>
      <p v-if="error" class="error" role="alert">{{ error }}</p>
      <div class="actions"><button type="button" :disabled="busy" @click="close">取消</button><button class="primary" :class="{danger: mode === 'delete'}" type="submit" :disabled="busy">{{ busy ? '处理中…' : mode === 'delete' ? '移入回收站' : '保存' }}</button></div>
    </form>
  </dialog>
</template>
<style scoped>
.dialog{width:min(440px,calc(100vw - 32px));border:0;border-radius:16px;box-shadow:0 20px 70px #1e344950;padding:26px;color:#263e58}.dialog::backdrop{background:#12243b99}.form{display:flex;flex-direction:column;gap:12px}.form h2{font-size:19px;margin:0}.form label{font-size:13px;color:#50677e}.form input{height:42px;border:1px solid #dce5ee;border-radius:8px;padding:0 11px;font:inherit}.detail{color:#60758a;font-size:13px;line-height:1.7;overflow-wrap:anywhere}.error{color:#b34f4c;font-size:12px;line-height:1.5}.actions{display:flex;justify-content:flex-end;gap:10px}.actions button{border:1px solid #dce5ef;background:white;color:#476079;border-radius:8px;padding:10px 15px}.actions .primary{background:#315d9b;border-color:#315d9b;color:white}.actions .danger{background:#b34646;border-color:#b34646}
</style>
