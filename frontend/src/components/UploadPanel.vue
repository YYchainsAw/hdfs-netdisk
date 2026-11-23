<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, shallowRef } from 'vue'
import { useUpload } from '../composables/useUpload'
const props = defineProps<{ userId: string; parentId: string }>()
const emit = defineEmits<{ expired: []; uploaded: [parentId: string] }>()
const fileInput = ref<HTMLInputElement | null>(null)
const { saved, phase, message, progress, hasSelected, start, resume, retry, resolve, cancel } = useUpload(props.userId, () => emit('expired'), parentId => emit('uploaded', parentId))
const busy = computed(() => ['checking', 'uploading', 'completing'].includes(phase.value))
interface UploadPolicy { allowedExtensions: string[]; maxSizeBytes: string }
const policy = shallowRef<UploadPolicy | null>(null)
const policyError = shallowRef('')
const policyLoading = shallowRef(false)
const selectionError = shallowRef('')
let policyRequest: AbortController | undefined
const accept = computed(() => saved.value || !policy.value ? undefined : policy.value.allowedExtensions.map(ext => `.${ext}`).join(','))
const maxSize = computed(() => Number(policy.value?.maxSizeBytes))
function formatLimit(bytes: number) {
  return bytes % (1024 ** 3) === 0 ? `${bytes / (1024 ** 3)} GiB` : `${(bytes / (1024 ** 2)).toFixed(1)} MiB`
}
async function loadPolicy() {
  policyRequest?.abort(); const controller = new AbortController(); policyRequest = controller
  policyLoading.value = true; policyError.value = ''
  try {
    const response = await fetch('/api/v1/uploads/policy', { credentials: 'same-origin', cache: 'no-store', signal: controller.signal })
    if (response.status === 401) { emit('expired'); return }
    if (!response.ok) throw new Error('获取上传规则失败，请重试。')
    const { data } = await response.json() as { data?: UploadPolicy }
    const max = Number(data?.maxSizeBytes)
    if (!data || !Array.isArray(data.allowedExtensions) || !data.allowedExtensions.length ||
        !data.allowedExtensions.every(ext => typeof ext === 'string' && /^[a-z0-9]{1,16}$/.test(ext)) ||
        !Number.isSafeInteger(max) || max <= 0) throw new Error('服务器上传规则无效，请重试。')
    policy.value = data
  } catch (e) {
    if (!controller.signal.aborted) { policy.value = null; policyError.value = e instanceof Error ? e.message : '获取上传规则失败，请重试。' }
  } finally { if (policyRequest === controller) policyLoading.value = false }
}
function choose() { selectionError.value = ''; fileInput.value?.click() }
function select(event: Event) {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  input.value = ''
  if (!file) return
  if (saved.value) { resume(file); return }
  if (!policy.value) { selectionError.value = '上传规则尚不可用，请重试后选择文件。'; return }
  const normalized = file.name.normalize('NFC')
  const dot = normalized.lastIndexOf('.')
  const ext = dot > 0 ? normalized.slice(dot + 1).toLowerCase() : ''
  if (!ext || !policy.value.allowedExtensions.includes(ext)) {
    selectionError.value = '文件后缀不在允许列表内，请重新选择。'; return
  }
  if (file.size > maxSize.value) { selectionError.value = `文件超过 ${formatLimit(maxSize.value)} 的大小限制。`; return }
  selectionError.value = ''; start(file, props.parentId)
}
function forget() { if (window.confirm('确定取消上传？已传分片会安排清理。')) void cancel() }
onMounted(() => { void loadPolicy() })
onBeforeUnmount(() => policyRequest?.abort())
</script>
<template>
  <section class="uploader" aria-label="文件上传">
    <div class="heading"><div><strong>上传文件</strong><p>支持分片上传与中断后继续<span v-if="policy">，单文件不超过 {{ formatLimit(maxSize) }}</span>。</p></div><button class="primary" :disabled="busy || phase === 'conflict' || (!saved && !policy)" @click="choose">{{ saved ? '选择原文件继续' : '选择文件' }}</button></div>
    <p v-if="policyLoading && !policy" class="rule">正在获取上传规则…</p>
    <p v-else-if="policyError" class="rule-error" role="alert">{{ policyError }} <button @click="loadPolicy">重试</button><span v-if="saved">；已有任务仍可继续或取消。</span></p>
    <details v-else-if="policy" class="rule"><summary>查看允许的文件后缀</summary><p>{{ policy.allowedExtensions.map(ext => `.${ext}`).join('、') }}。仅检查文件名后缀，不能证明文件内容安全。</p></details>
    <input ref="fileInput" type="file" :accept="accept" hidden @change="select" />
    <p v-if="selectionError" class="rule-error" role="alert">{{ selectionError }}</p>
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
.uploader{background:#fff;border:1px solid #e5edf6;border-radius:12px;padding:16px 19px;margin:24px 0 16px;color:#334a65}.heading{display:flex;gap:16px;justify-content:space-between;align-items:center}.heading strong{font-size:14px}.heading p,.task p,.rule{margin:5px 0 0;font-size:12px;color:#7890a8;line-height:1.6}.rule summary{cursor:pointer;color:#4d7095}.rule p{margin:5px 0;overflow-wrap:anywhere}.rule-error{color:#a64b46;font-size:12px;line-height:1.6}.rule-error button{background:none;border:0;color:#3c67a9;text-decoration:underline;cursor:pointer}.primary{padding:10px 15px;background:#3c67a9;border:0;border-radius:8px;color:white;white-space:nowrap}.primary:disabled{opacity:.5}.task{margin-top:14px;padding-top:12px;border-top:1px solid #edf1f6}.task-title{display:flex;justify-content:space-between;gap:12px;font-size:12px;overflow-wrap:anywhere}.task progress{width:100%;height:7px;accent-color:#3c67a9;margin-top:8px}.task-actions{display:flex;gap:8px;flex-wrap:wrap;margin-top:10px}.task-actions button{border:1px solid #dbe4ef;background:#f7faff;border-radius:7px;padding:7px 10px;color:#385d88;font-size:12px}@media(max-width:560px){.heading{align-items:flex-start}.heading p{max-width:220px}}
</style>
