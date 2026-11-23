<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, shallowRef, useTemplateRef } from 'vue'
import type { NodeItem } from '../composables/useNodes'
const props = defineProps<{ item: NodeItem }>()
const emit = defineEmits<{ close: []; expired: [] }>()
const dialog = useTemplateRef<HTMLDialogElement>('dialog')
const loading = shallowRef(true)
const error = shallowRef('')
const text = shallowRef('')
const objectUrl = shallowRef('')
const controller = new AbortController()
const ext = computed(() => props.item.name.split('.').pop()?.toLowerCase() ?? '')
const isText = computed(() => ['txt','md','csv','json'].includes(ext.value))
const isPdf = computed(() => ext.value === 'pdf')
async function load() {
  try {
    const res = await fetch(`/api/v1/nodes/${encodeURIComponent(props.item.id)}/preview`, { credentials:'same-origin', cache:'no-store', signal:controller.signal })
    if (!res.ok) {
      if (res.status === 401) { emit('expired'); return }
      let message = '预览失败，请下载后查看。'
      try { const body = await res.json(); if (typeof body.error?.message === 'string') message = body.error.message } catch { /* proxy response may be empty */ }
      throw new Error(message)
    }
    if (isText.value) text.value = await res.text()
    else objectUrl.value = URL.createObjectURL(await res.blob())
  } catch (e) {
    if (!controller.signal.aborted) error.value = e instanceof Error ? e.message : '预览失败，请下载后查看。'
  } finally { if (!controller.signal.aborted) loading.value = false }
}
onMounted(() => { dialog.value?.showModal(); void load() })
onBeforeUnmount(() => { controller.abort(); if (objectUrl.value) URL.revokeObjectURL(objectUrl.value) })
</script>
<template>
  <dialog ref="dialog" class="preview" aria-labelledby="preview-title" @cancel.prevent="emit('close')">
    <header><h2 id="preview-title">{{ item.name }}</h2><button type="button" aria-label="关闭预览" @click="emit('close')">✕</button></header>
    <div class="body">
      <p v-if="loading" role="status">正在加载预览…</p>
      <p v-else-if="error" role="alert">{{ error }} <a :href="`/api/v1/nodes/${encodeURIComponent(item.id)}/content`">下载文件</a></p>
      <pre v-else-if="isText">{{ text }}</pre>
      <iframe v-else-if="isPdf" :src="objectUrl" title="PDF 文件预览"></iframe>
      <img v-else :src="objectUrl" :alt="item.name" />
    </div>
    <footer><a :href="`/api/v1/nodes/${encodeURIComponent(item.id)}/content`">下载</a><button type="button" @click="emit('close')">关闭</button></footer>
  </dialog>
</template>
<style scoped>
.preview{width:min(940px,94vw);height:min(80vh,760px);max-height:90vh;border:0;border-radius:14px;box-shadow:0 18px 60px #1b35553d;padding:0;color:#30465f}.preview::backdrop{background:#10243d80}.preview[open]{display:flex;flex-direction:column}.preview header,.preview footer{display:flex;align-items:center;justify-content:space-between;gap:16px;padding:14px 20px;border-bottom:1px solid #e8edf3}.preview h2{font-size:15px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;margin:0}.preview button,.preview a{border:0;background:none;color:#416e9e;cursor:pointer;text-decoration:none;font-size:13px}.body{flex:1;min-height:0;overflow:auto;padding:16px;display:flex;align-items:flex-start;justify-content:center;background:#f6f8fb}.body p{color:#74879b}.body pre{width:100%;white-space:pre-wrap;overflow-wrap:anywhere;margin:0;background:#fff;padding:16px;font-size:13px;line-height:1.6}.body img{max-width:100%;max-height:100%;object-fit:contain}.body iframe{width:100%;height:100%;border:0;background:#fff}.preview footer{justify-content:flex-end;border-bottom:0;border-top:1px solid #e8edf3}
</style>



