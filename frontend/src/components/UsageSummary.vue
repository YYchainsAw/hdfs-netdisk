<script setup lang="ts">
import { onBeforeUnmount, onMounted, shallowRef, watch } from 'vue'
import { AccountError, accountRequest } from '../composables/useAccountFeatures'
const props = defineProps<{ refresh: number }>()
const emit = defineEmits<{ expired: [] }>()
const used = shallowRef(''), files = shallowRef(''), error = shallowRef('')
let requestId = 0
async function load() {
  const id = ++requestId
  try {
    const result = await accountRequest<{usedBytes: string; fileCount: string}>('/usage')
    if (id !== requestId) return
    const n = Number(result.usedBytes)
    used.value = Number.isFinite(n) ? n < 1024 ? `${n} B` : n < 1048576 ? `${(n/1024).toFixed(1)} KiB` : n < 1073741824 ? `${(n/1048576).toFixed(1)} MiB` : `${(n/1073741824).toFixed(2)} GiB` : `${result.usedBytes} B`
    files.value = result.fileCount; error.value = ''
  } catch (e) {
    if (id !== requestId) return
    if (e instanceof AccountError && e.status === 401) emit('expired')
    else error.value = '用量暂不可用'
  }
}
onMounted(load)
onBeforeUnmount(() => { requestId++ })
watch(() => props.refresh, load)
</script>
<template><div class="usage" role="status"><template v-if="error">{{ error }} <button @click="load">重试</button></template><template v-else-if="used">已使用 {{ used }} · {{ files }} 个文件 <span class="usage-note">（含回收站及待清理文件，不含上传临时分片）</span></template><template v-else>正在获取存储用量…</template></div></template>
<style scoped>.usage{margin:16px 0;color:#71869c;font-size:12px}.usage-note{color:#8b9bac}.usage button{border:0;background:none;color:#3c67a9;cursor:pointer}</style>
