<script setup lang="ts">
import { nextTick, shallowRef } from 'vue'
import { useTrash, type TrashItem } from '../composables/useTrash'
import TrashActionDialog from './TrashActionDialog.vue'
const emit = defineEmits<{ expired: []; changed: [] }>()
const { items, loading, saving, error, actionError, page, hasMore, load, apply } = useTrash(() => emit('expired'), () => emit('changed'))
const action = shallowRef<{ item: TrashItem; operation: 'restore' | 'purge' } | null>(null)
const notice = shallowRef('')
let returnFocus: HTMLElement | null = null
function show(item: TrashItem, operation: 'restore' | 'purge') { returnFocus = document.activeElement instanceof HTMLElement ? document.activeElement : null; actionError.value = ''; action.value = { item, operation } }
function close() { if (saving.value) return; action.value = null; void nextTick(() => returnFocus?.focus()) }
async function confirm() {
  if (!action.value) return
  const operation = action.value.operation
  if (await apply(action.value.item, operation)) { action.value = null; notice.value = operation === 'restore' ? '已恢复到原文件夹。' : '已安排彻底删除，后台将清理存储数据。'; void nextTick(() => returnFocus?.focus()) }
}
function date(value: string) { const d = new Date(value); return Number.isNaN(d.valueOf()) ? value : new Intl.DateTimeFormat('zh-CN', { dateStyle: 'medium', timeStyle: 'short' }).format(d) }
</script>
<template>
  <section class="trash" aria-label="回收站">
    <div class="head"><div><div class="title">回收站</div><p>删除的项目可以恢复到原文件夹，彻底删除后无法恢复。</p></div><button type="button" :disabled="loading || saving" @click="load(page)">刷新</button></div>
    <p v-if="error" class="error" role="alert">{{ error }} <button @click="load(page)">重试</button></p>
    <div v-else-if="loading" class="placeholder" role="status">正在加载回收站…</div>
    <div v-else-if="!items.length" class="placeholder" role="status">回收站里没有项目</div>
    <ul v-else class="list"><li v-for="item in items" :key="item.id" class="row"><div class="item"><span class="icon" aria-hidden="true">{{ item.type === 'FOLDER' ? '▰' : '▤' }}</span><div class="copy"><strong>{{ item.name }}</strong><small>{{ item.type === 'FOLDER' ? '文件夹及其内容' : '文件' }} · 删除于 {{ date(item.deletedAt) }}</small></div></div><div class="actions"><button :disabled="saving" :aria-label="`恢复 ${item.name}`" @click="show(item,'restore')">恢复</button><button :disabled="saving" :aria-label="`彻底删除 ${item.name}`" @click="show(item,'purge')">彻底删除</button></div></li></ul>
    <footer class="footer"><span>第 {{ page }} 页 · {{ items.length }} 项</span><div><button :disabled="page <= 1 || loading || saving" @click="load(page - 1)">上一页</button><button :disabled="!hasMore || loading || saving" @click="load(page + 1)">下一页</button></div></footer>
    <p v-if="notice" class="notice" role="status">{{ notice }}</p>
    <TrashActionDialog v-if="action" :item="action.item" :operation="action.operation" :busy="saving" :error="actionError" @cancel="close" @confirm="confirm" />
  </section>
</template>
<style scoped>
.trash{background:#fff;border:1px solid #e8edf4;border-radius:13px;box-shadow:0 10px 30px #253e5c09;overflow:hidden}.head{display:flex;align-items:center;justify-content:space-between;gap:16px;padding:24px}.title{font-size:15px;font-weight:700;color:#263d57}.head p{font-size:12px;color:#8294a8;margin:8px 0 0;line-height:1.6}.head button,.footer button,.error button{border:0;background:transparent;color:#55779b;cursor:pointer}.list{list-style:none;padding:0;margin:0}.row{display:flex;justify-content:space-between;align-items:center;gap:16px;min-height:70px;padding:12px 24px;border-top:1px solid #f1f4f8}.item{display:flex;align-items:center;gap:14px;min-width:0}.icon{width:42px;height:42px;display:grid;place-items:center;border-radius:12px;background:#f1f4f8;color:#7990a8;flex:none}.copy{min-width:0}.copy strong{display:block;font-size:14px;color:#334860;overflow-wrap:anywhere}.copy small{display:block;font-size:11px;color:#8c9aab;margin-top:4px}.actions{display:flex;gap:12px;flex:none}.actions button{border:0;background:transparent;color:#55779b;cursor:pointer;font-size:12px}.actions button:last-child{color:#aa6663}.placeholder{min-height:220px;display:grid;place-items:center;color:#8d9eaf;font-size:13px}.footer{display:flex;justify-content:space-between;padding:15px 24px;color:#a0adbb;font-size:11px}.footer div{display:flex;gap:12px}.error{padding:20px;color:#b34f4c;font-size:13px}.notice{margin:0;padding:0 24px 15px;color:#47856b;font-size:13px}@media(max-width:560px){.head,.row{padding:16px}.row{align-items:flex-start;flex-wrap:wrap}.actions{margin-left:56px}}
</style>
