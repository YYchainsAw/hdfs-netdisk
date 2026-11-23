<script setup lang="ts">
import { computed, onMounted, ref, shallowRef } from 'vue'
import type { NodeItem } from '../composables/useNodes'

const props = defineProps<{ items: NodeItem[]; sourceId: string; busy: boolean }>()
const emit = defineEmits<{ cancel: []; moved: []; expired: [] }>()
const path = ref<{ id: string; name: string }[]>([{ id: '0', name: '我的文件' }])
const folders = ref<NodeItem[]>([])
const page = shallowRef(1)
const hasMore = shallowRef(false)
const loading = shallowRef(false)
const saving = shallowRef(false)
const error = shallowRef('')
const target = computed(() => path.value[path.value.length - 1]!.id)
let generation = 0
async function load(next = 1) {
  const seq = ++generation
  const parentId = target.value
  loading.value = true; error.value = ''
  try {
    const qs = new URLSearchParams({ parentId, page: String(next), pageSize: '100' })
    const res = await fetch(`/api/v1/nodes?${qs}`, { credentials: 'same-origin', cache: 'no-store' })
    if (res.status === 401) { emit('expired'); return }
    if (!res.ok) throw new Error('无法读取目标文件夹，请重试。')
    const payload = await res.json()
    if (seq !== generation) return
    folders.value = (payload.data.items as NodeItem[]).filter(x => x.type === 'FOLDER')
    page.value = payload.data.page; hasMore.value = payload.data.hasMore
  } catch (e) { if (seq === generation) error.value = e instanceof Error ? e.message : '无法读取文件夹。' }
  finally { if (seq === generation) loading.value = false }
}
function open(folder: NodeItem) { path.value.push({ id: folder.id, name: folder.name }); void load() }
function go(index: number) { path.value = path.value.slice(0, index + 1); void load() }
async function submit() {
  if (saving.value || target.value === props.sourceId) return
  saving.value = true; error.value = ''
  try {
    const tokenRes = await fetch('/api/v1/auth/csrf', { credentials: 'same-origin', cache: 'no-store' })
    if (tokenRes.status === 401) { emit('expired'); return }
    if (!tokenRes.ok) throw new Error('安全验证失败，请重试。')
    const token = (await tokenRes.json()).data.csrfToken
    const res = await fetch('/api/v1/nodes/move', {
      method: 'POST', credentials: 'same-origin', headers: { 'Content-Type': 'application/json', 'X-CSRF-TOKEN': token },
      body: JSON.stringify({ targetParentId: target.value, items: props.items.map(x => ({ id: x.id, version: x.version })) }),
    })
    if (res.status === 401) { emit('expired'); return }
    if (!res.ok) {
      let message = '移动失败，请重试。'
      try { const body = await res.json(); if (body.error?.message) message = body.error.message } catch { /* gateway may return non-JSON */ }
      throw new Error(message)
    }
    emit('moved')
  } catch (e) { error.value = e instanceof Error ? e.message : '无法连接服务器。' }
  finally { saving.value = false }
}
onMounted(() => { void load() })
</script>
<template>
  <div class="overlay" @click.self="!saving && emit('cancel')">
    <section class="dialog" role="dialog" aria-modal="true" aria-labelledby="move-title" @keydown.esc="!saving && emit('cancel')">
      <h2 id="move-title">选择目标文件夹</h2><p>将 {{ items.length }} 个项目移动到：</p>
      <nav aria-label="目标路径" class="crumbs"><template v-for="(item,index) in path" :key="item.id"><span v-if="index"> / </span><button @click="go(index)">{{ item.name }}</button></template></nav>
      <p v-if="loading">正在加载文件夹…</p>
      <ul v-else class="folders"><li v-for="folder in folders" :key="folder.id"><button @click="open(folder)">▰ {{ folder.name }} <span>›</span></button></li><li v-if="!folders.length">当前页没有文件夹</li></ul>
      <div class="pages"><button :disabled="page<=1 || loading" @click="load(page-1)">上一页</button><span>第 {{ page }} 页</span><button :disabled="!hasMore || loading" @click="load(page+1)">下一页</button></div>
      <p v-if="target===sourceId" class="hint">请选择不同的目标文件夹。</p><p v-if="error" class="error" role="alert">{{ error }}</p>
      <footer><button :disabled="saving" @click="emit('cancel')">取消</button><button class="primary" :disabled="saving || loading || target===sourceId" @click="submit">{{ saving ? '移动中…' : '移动到这里' }}</button></footer>
    </section>
  </div>
</template>
<style scoped>
.overlay{position:fixed;inset:0;background:#18283f88;display:grid;place-items:center;z-index:50;padding:16px}.dialog{background:white;border-radius:14px;padding:24px;width:min(100%,480px);box-shadow:0 18px 65px #14253b44;color:#304560}.dialog h2{margin:0;font-size:19px}.dialog p{font-size:13px;color:#71869b}.crumbs{display:flex;flex-wrap:wrap;gap:6px;color:#9aafc3}.crumbs button,.pages button{border:0;background:none;color:#3e6b9d;padding:3px}.folders{list-style:none;padding:8px 0;margin:12px 0;border:1px solid #e5ecf4;border-radius:8px;max-height:240px;min-height:80px;overflow:auto}.folders li{padding:3px 12px;color:#8a9cae;font-size:13px}.folders button{width:100%;border:0;background:none;text-align:left;padding:8px;color:#3d5773}.folders button:hover{background:#f0f5fb}.folders span{float:right}.pages{display:flex;gap:12px;justify-content:center;font-size:12px;color:#8a9cae}.hint{color:#8d7895}.dialog .error{color:#ad4f4f}.dialog footer{display:flex;justify-content:flex-end;gap:8px;margin-top:18px}.dialog footer button{border:1px solid #dbe4ef;border-radius:8px;background:white;padding:9px 14px;color:#516a84}.dialog footer .primary{background:#3c67a9;color:white;border-color:#3c67a9}.dialog button:disabled{opacity:.5}
</style>
