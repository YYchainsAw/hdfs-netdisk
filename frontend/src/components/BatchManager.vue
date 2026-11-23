<script setup lang="ts">
import { computed, ref, shallowRef, watch } from 'vue'
import type { NodeItem } from '../composables/useNodes'
import MoveDialog from './MoveDialog.vue'
const props = defineProps<{ items: NodeItem[]; parentId: string; busy: boolean }>()
const emit = defineEmits<{ changed: []; expired: [] }>()
const selected = ref<string[]>([])
const moving = shallowRef(false)
const deleting = shallowRef(false)
const message = shallowRef('')
const chosen = computed(() => props.items.filter(item => selected.value.includes(item.id)))
watch(() => props.parentId, () => { selected.value = []; message.value = '' })
watch(() => props.items, current => { const ids = new Set(current.map(x => x.id)); selected.value = selected.value.filter(id => ids.has(id)) })
function toggleAll() { selected.value = chosen.value.length === props.items.length ? [] : props.items.map(item => item.id) }
function moved() { moving.value = false; selected.value = []; message.value = '移动完成。'; emit('changed') }
async function remove() {
  if (!chosen.value.length || deleting.value || !window.confirm(`确定删除选中的 ${chosen.value.length} 个项目吗？文件删除后不可恢复；非空文件夹无法删除。`)) return
  deleting.value = true; message.value = ''
  let success = 0, failed = 0
  const failures: string[] = []
  try {
    const tokenRes = await fetch('/api/v1/auth/csrf', { credentials: 'same-origin', cache: 'no-store' })
    if (tokenRes.status === 401) { emit('expired'); return }
    if (!tokenRes.ok) throw new Error('安全验证失败，请重试。')
    const token = (await tokenRes.json()).data.csrfToken
    for (const item of chosen.value) {
      try {
        const res = await fetch(`/api/v1/nodes/${encodeURIComponent(item.id)}`, { method:'DELETE', credentials:'same-origin', headers:{ 'X-CSRF-TOKEN':token, 'If-Match':`"v${item.version}"` } })
        if (res.status === 401) { emit('expired'); return }
        if (!res.ok) { let why = '操作失败'; try { const b = await res.json(); if (b.error?.message) why = b.error.message } catch {} ; throw new Error(why) }
        success++
      } catch (e) { failed++; failures.push(`${item.name}：${e instanceof Error ? e.message : '连接失败'}`) }
    }
    message.value = `删除完成：成功 ${success} 项，失败 ${failed} 项。${failures.length ? ' ' + failures.join('；') : ' 文件后台清理期间原名称可能暂时不可用。'}`
    selected.value = []; emit('changed')
  } catch (e) { message.value = e instanceof Error ? e.message : '无法连接服务器。' }
  finally { deleting.value = false }
}
</script>
<template>
  <details class="manage">
    <summary>移动或批量删除 <span>选择当前页的文件与文件夹</span></summary>
    <div v-if="!items.length" class="empty">当前页没有可选择的项目。</div>
    <template v-else>
      <div class="controls"><button :disabled="busy || deleting" @click="toggleAll">{{ chosen.length===items.length ? '取消全选' : '全选当前页' }}</button><span>已选择 {{ chosen.length }} 项</span><button :disabled="!chosen.length || busy || deleting" @click="moving=true">移动</button><button class="delete" :disabled="!chosen.length || busy || deleting" @click="remove">{{ deleting ? '删除中…' : '批量删除' }}</button></div>
      <ul class="choices"><li v-for="item in items" :key="item.id"><label><input v-model="selected" :disabled="busy || deleting" type="checkbox" :value="item.id" /> <span>{{ item.type === 'FOLDER' ? '▰' : '▤' }} {{ item.name }}</span></label></li></ul>
    </template>
    <p v-if="message" class="message" role="status">{{ message }}</p>
    <MoveDialog v-if="moving" :items="chosen" :source-id="parentId" :busy="busy" @cancel="moving=false" @moved="moved" @expired="emit('expired')" />
  </details>
</template>
<style scoped>
.manage{margin:0 0 18px;border:1px solid #e2eaf3;border-radius:10px;background:white;color:#536b84;font-size:13px}.manage summary{cursor:pointer;padding:13px 16px;font-weight:600;color:#3d628c}.manage summary span{font-weight:400;color:#96a7b9;font-size:12px;margin-left:9px}.controls{display:flex;align-items:center;gap:10px;flex-wrap:wrap;padding:0 16px 10px}.controls span{font-size:12px;color:#8a9db1;margin-right:auto}.controls button{border:1px solid #dce6f0;background:#fff;border-radius:7px;padding:7px 10px;color:#4b6d91}.controls button.delete{color:#aa6262}.controls button:disabled{opacity:.45}.choices{list-style:none;margin:0 16px 12px;padding:0;max-height:200px;overflow:auto;border:1px solid #edf1f6;border-radius:8px}.choices li{border-bottom:1px solid #f3f6f9}.choices label{display:flex;gap:8px;align-items:center;padding:9px 12px}.choices span{overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.message,.empty{margin:0;padding:0 16px 14px;color:#6d849d;line-height:1.6}.message{overflow-wrap:anywhere}@media(max-width:560px){.manage summary span{display:block;margin:5px 0 0}.controls{gap:6px}}
</style>
