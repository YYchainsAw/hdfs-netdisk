<script setup lang="ts">
import { computed, ref } from 'vue'
import { useUpload } from '../composables/useUpload'
const props = defineProps<{ userId: string; parentId: string }>()
const emit = defineEmits<{ expired: []; uploaded: [parentId: string] }>()
const fileInput = ref<HTMLInputElement | null>(null)
const { saved, phase, message, progress, hasSelected, start, resume, retry, resolve, cancel } = useUpload(props.userId, () => emit('expired'), parentId => emit('uploaded', parentId))
const busy = computed(() => ['checking', 'uploading', 'completing'].includes(phase.value))
function choose() { fileInput.value?.click() }
function select(event: Event) {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  input.value = ''
  if (!file) return
  if (saved.value) resume(file); else start(file, props.parentId)
}
function forget() {
  if (window.confirm('确定取消上传？已传分片会安排清理。')) void cancel()
}
</script>
<template>
  <section class="uploader" aria-label="文件上传">
    <div class="heading"><div><strong>上传文件</strong><p>支持分片上传与中断后继续，单文件不超过 2 GiB。</p></div><button class="primary" :disabled="busy || phase === 'conflict'" @click="choose">{{ saved ? '选择原文件继续' : '选择文件' }}</button></div>
    <input ref="fileInput" type="file" hidden @change="select" />
    <div v-if="saved || message" class="task" role="status" aria-live="polite">
      <div class="task-title"><span>{{ saved?.name || '上传任务' }}</span><span v-if="saved">{{ progress }}%</span></div>
      <progress v-if="saved" :value="progress" max="100" :aria-label="`上传进度 ${progress}%`" />
      <p>{{ message }}</p>
      <div v-if="phase === 'conflict'" class="task-actions"><button @click="resolve('KEEP_BOTH')">保留两份</button><button @click="resolve('SKIP')">跳过</button><button @click="forget">取消上传</button></div>
      <div v-else-if="phase === 'failed' && saved" class="task-actions"><button v-if="hasSelected" @click="retry">重试当前文件</button><button @click="choose">重新选择文件</button><button @click="forget">取消上传</button></div>
    </div>
  </section>
</template>
<style scoped>
.uploader{background:#fff;border:1px solid #e5edf6;border-radius:12px;padding:16px 19px;margin:24px 0 16px;color:#334a65}.heading{display:flex;gap:16px;justify-content:space-between;align-items:center}.heading strong{font-size:14px}.heading p,.task p{margin:5px 0 0;font-size:12px;color:#7890a8;line-height:1.6}.primary{padding:10px 15px;background:#3c67a9;border:0;border-radius:8px;color:white;white-space:nowrap}.primary:disabled{opacity:.5}.task{margin-top:14px;padding-top:12px;border-top:1px solid #edf1f6}.task-title{display:flex;justify-content:space-between;gap:12px;font-size:12px;overflow-wrap:anywhere}.task progress{width:100%;height:7px;accent-color:#3c67a9;margin-top:8px}.task-actions{display:flex;gap:8px;flex-wrap:wrap;margin-top:10px}.task-actions button{border:1px solid #dbe4ef;background:#f7faff;border-radius:7px;padding:7px 10px;color:#385d88;font-size:12px}@media(max-width:560px){.heading{align-items:flex-start}.heading p{max-width:220px}}
</style>


