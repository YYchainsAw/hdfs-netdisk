<script setup lang="ts">
import type { NodeItem } from '../composables/useNodes'

defineProps<{ items: NodeItem[]; loading: boolean; keyword: string }>()
defineEmits<{ open: [node: NodeItem]; preview: [node: NodeItem]; share: [node: NodeItem]; action: [node: NodeItem, mode: 'rename' | 'delete'] }>()
function canPreview(node: NodeItem) { return node.type === 'FILE' && /\.(png|jpe?g|gif|webp|pdf|txt|md|csv|json)$/i.test(node.name) }

function date(value: string | null | undefined) {
  if (!value) return '—'
  const d = new Date(value)
  return Number.isNaN(d.valueOf()) ? '—' : new Intl.DateTimeFormat('zh-CN', { year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' }).format(d)
}
function type(node: NodeItem) {
  if (node.type === 'FOLDER') return '文件夹'
  // The label reflects the name's suffix, not inspected content.
  const i = node.name.lastIndexOf('.')
  const ext = i > 0 ? node.name.slice(i + 1) : ''
  return /^[a-zA-Z0-9]{1,16}$/.test(ext) ? `${ext.toUpperCase()} 文件` : '文件'
}
function size(value: string | null) {
  if (value === null) return '—'
  const n = Number(value)
  if (!Number.isFinite(n)) return value + ' B'
  if (n < 1024) return n + ' B'
  if (n < 1048576) return (n / 1024).toFixed(1) + ' KB'
  return (n / 1048576).toFixed(1) + ' MB'
}
</script>
<template>
  <div v-if="loading" class="placeholder" role="status">正在加载文件…</div>
  <div v-else-if="!items.length" class="placeholder" role="status"><span class="empty-icon">▱</span><b>{{ keyword ? '没有找到匹配的文件' : '这里还没有文件' }}</b><span>{{ keyword ? '试试其他名称。' : '从创建一个文件夹开始。' }}</span></div>
  <div v-else>
    <div class="header"><span>名称</span><span>类型</span><span>上传 / 创建时间</span><span>大小</span><span>操作</span></div>
    <ul class="list">
      <li v-for="item in items" :key="item.id" class="row">
        <div class="name"><span :class="['icon', item.type === 'FOLDER' ? 'folder' : 'file']" aria-hidden="true">{{ item.type === 'FOLDER' ? '▰' : '▤' }}</span><div class="name-copy"><button v-if="item.type === 'FOLDER'" class="folder-name" @click="$emit('open',item)">{{ item.name }}</button><span v-else class="file-name">{{ item.name }}</span><small class="mobile-meta">{{ type(item) }} · {{ size(item.sizeBytes) }}<br>{{ item.type === 'FOLDER' ? '创建' : '上传' }}：{{ date(item.createdAt) }}</small></div></div>
        <span class="file-type">{{ type(item) }}</span><span class="date">{{ date(item.createdAt) }}</span><span class="size">{{ size(item.sizeBytes) }}</span>
        <div class="row-actions"><button v-if="canPreview(item)" type="button" :aria-label="`预览 ${item.name}`" @click="$emit('preview',item)">预览</button><button v-if="item.type === 'FILE'" type="button" :aria-label="`分享 ${item.name}`" @click="$emit('share',item)">分享</button><a v-if="item.type === 'FILE'" class="download" :href="`/api/v1/nodes/${encodeURIComponent(item.id)}/content`">下载</a><button type="button" :aria-label="`重命名 ${item.name}`" @click="$emit('action',item,'rename')">重命名</button><button type="button" :aria-label="`删除 ${item.name}`" @click="$emit('action',item,'delete')">删除</button></div>
      </li>
    </ul>
  </div>
</template>
<style scoped>
.header,.row{display:grid;grid-template-columns:minmax(0,1fr) 100px 150px 85px 190px;align-items:center;gap:14px}.header{padding:13px 24px;color:#99a6b5;font-size:12px;border-bottom:1px solid #edf1f6}.list{list-style:none;padding:0;margin:0}.row{min-height:70px;padding:10px 24px;border-bottom:1px solid #f1f4f8}.row:hover{background:#f8fafd}.name{display:flex;align-items:center;gap:14px;min-width:0}.icon{width:42px;height:42px;display:grid;place-items:center;border-radius:12px;flex:none;font-size:21px}.folder{background:#fdf4e3;color:#daa53f}.file{background:#edf3fc;color:#668abc}.name-copy{min-width:0}.folder-name,.file-name{font-size:14px;font-weight:600;color:#334860;overflow:hidden;white-space:nowrap;text-overflow:ellipsis;display:block;max-width:100%}.folder-name{border:0;padding:0;background:none;text-align:left}.folder-name:hover{color:#315f9e;text-decoration:underline}.date,.size,.file-type{font-size:12px;color:#71839a;overflow-wrap:anywhere}.row-actions{display:flex;gap:12px;align-items:center;white-space:nowrap}.row-actions button{border:0;background:none;padding:3px 0;color:#55779b;cursor:pointer;font-size:12px}.row-actions button:last-child{color:#aa6663}.download{font-size:12px;color:#3c67a9;text-decoration:none}.download:hover{text-decoration:underline}.mobile-meta{display:none}.placeholder{min-height:260px;display:flex;flex-direction:column;gap:10px;align-items:center;justify-content:center;color:#8d9eaf;font-size:13px}.placeholder b{font-size:15px;color:#3c526c}.empty-icon{font-size:36px;color:#8caac8;background:#f3f7fc;border-radius:18px;width:65px;height:65px;text-align:center;line-height:65px}@media(max-width:1000px){.header,.row{grid-template-columns:minmax(0,1fr) 85px 135px 70px 190px;gap:8px}}@media(max-width:820px){.header{display:none}.row{grid-template-columns:minmax(0,1fr) auto;padding:12px 16px}.date,.size,.file-type{display:none}.mobile-meta{display:block;color:#71839a;font-size:11px;line-height:1.5;margin-top:4px}}
</style>
