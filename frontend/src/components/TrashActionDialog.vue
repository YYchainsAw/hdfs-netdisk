<script setup lang="ts">
import { onMounted, useTemplateRef } from 'vue'
import type { TrashItem } from '../composables/useTrash'
const props = defineProps<{ item: TrashItem; operation: 'restore' | 'purge'; busy: boolean; error: string }>()
const emit = defineEmits<{ cancel: []; confirm: [] }>()
const dialog = useTemplateRef<HTMLDialogElement>('dialog')
onMounted(() => dialog.value?.showModal())
function close() { if (!props.busy) emit('cancel') }
</script>
<template>
  <dialog ref="dialog" class="dialog" @cancel.prevent="close">
    <form class="form" @submit.prevent="emit('confirm')">
      <h2>{{ operation === 'restore' ? '恢复项目' : '彻底删除' }}</h2>
      <p v-if="operation === 'restore'" class="detail">将“{{ item.name }}”恢复到原来的文件夹。{{ item.type === 'FOLDER' ? '文件夹内的内容也会一起恢复。' : '' }}</p>
      <p v-else class="detail">确定彻底删除“{{ item.name }}”吗？{{ item.type === 'FOLDER' ? '文件夹内所有内容也会一起删除。' : '' }}此操作不可恢复。</p>
      <p v-if="error" class="error" role="alert">{{ error }}</p>
      <div class="actions"><button type="button" :disabled="busy" @click="close">取消</button><button class="primary" :class="{ danger: operation === 'purge' }" type="submit" :disabled="busy">{{ busy ? '处理中…' : operation === 'restore' ? '确认恢复' : '确认彻底删除' }}</button></div>
    </form>
  </dialog>
</template>
<style scoped>
.dialog{width:min(440px,calc(100vw - 32px));border:0;border-radius:16px;box-shadow:0 20px 70px #1e344950;padding:26px;color:#263e58}.dialog::backdrop{background:#12243b99}.form{display:flex;flex-direction:column;gap:12px}.form h2{font-size:19px;margin:0}.detail{color:#60758a;font-size:13px;line-height:1.7;overflow-wrap:anywhere}.error{color:#b34f4c;font-size:12px;line-height:1.5}.actions{display:flex;justify-content:flex-end;gap:10px}.actions button{border:1px solid #dce5ef;background:white;color:#476079;border-radius:8px;padding:10px 15px}.actions .primary{background:#315d9b;border-color:#315d9b;color:white}.actions .danger{background:#b34646;border-color:#b34646}
</style>
