<script setup lang="ts">
import type { DiskNode } from '../types'

defineProps<{ items: DiskNode[] }>()
const emit = defineEmits<{
  open: [item: DiskNode]
  action: [item: DiskNode, kind: 'download' | 'rename' | 'move' | 'delete']
}>()

function sizeLabel(bytes: number | null): string {
  if (bytes === null) return '—'
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`
  if (bytes < 1024 * 1024 * 1024) return `${(bytes / (1024 * 1024)).toFixed(1)} MB`
  return `${(bytes / (1024 * 1024 * 1024)).toFixed(1)} GB`
}

function dateLabel(value: string): string {
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return value
  return new Intl.DateTimeFormat('zh-CN', { year: 'numeric', month: '2-digit', day: '2-digit' }).format(date)
}

function fileTag(name: string): string {
  const extension = name.includes('.') ? name.split('.').pop()?.toLowerCase() : ''
  if (['png', 'jpg', 'jpeg', 'webp', 'gif'].includes(extension ?? '')) return 'IMG'
  if (['pdf'].includes(extension ?? '')) return 'PDF'
  if (['doc', 'docx'].includes(extension ?? '')) return 'DOC'
  if (['xls', 'xlsx', 'csv'].includes(extension ?? '')) return 'XLS'
  if (['mp4', 'mov', 'webm'].includes(extension ?? '')) return 'VID'
  return 'FILE'
}

function choose(event: MouseEvent, item: DiskNode, kind: 'download' | 'rename' | 'move' | 'delete') {
  const menu = (event.currentTarget as HTMLElement).closest('details')
  if (menu) menu.open = false
  emit('action', item, kind)
}

function closeOnEscape(event: KeyboardEvent) {
  const menu = event.currentTarget as HTMLDetailsElement
  menu.open = false
  menu.querySelector('summary')?.focus()
}
</script>

<template>
  <div class="file-list">
    <div v-if="items.length" class="list-header" aria-hidden="true">
      <span>名称</span><span>修改时间</span><span>大小</span><span>操作</span>
    </div>

    <ul v-if="items.length" class="list-body" aria-label="文件列表">
      <li v-for="item in items" :key="item.id" class="file-row">
        <div class="name-cell">
          <span v-if="item.type === 'FOLDER'" class="file-icon folder-icon" aria-hidden="true">
            <svg viewBox="0 0 28 28" fill="none"><path d="M3.5 8.2A2.2 2.2 0 0 1 5.7 6h5.1l2.1 2.4h9.4a2.2 2.2 0 0 1 2.2 2.2v9.1a2.3 2.3 0 0 1-2.3 2.3H5.8a2.3 2.3 0 0 1-2.3-2.3V8.2Z" fill="currentColor" opacity=".26"/><path d="M3.5 10.2h21v9.5a2.3 2.3 0 0 1-2.3 2.3H5.8a2.3 2.3 0 0 1-2.3-2.3v-9.5Z" fill="currentColor" opacity=".78"/></svg>
          </span>
          <span v-else class="file-icon document-icon" :class="`tag-${fileTag(item.name).toLowerCase()}`" aria-hidden="true"><span>{{ fileTag(item.name) }}</span></span>
          <div class="name-copy">
            <button v-if="item.type === 'FOLDER'" class="folder-name" type="button" :title="item.name" @click="emit('open', item)">{{ item.name }}</button>
            <span v-else class="file-name" :title="item.name">{{ item.name }}</span>
            <span class="mobile-meta">{{ item.type === 'FOLDER' ? '文件夹' : sizeLabel(item.size) }} · {{ dateLabel(item.modified) }}</span>
          </div>
        </div>
        <span class="date-cell">{{ dateLabel(item.modified) }}</span>
        <span class="size-cell">{{ sizeLabel(item.size) }}</span>
        <div class="action-cell">
          <details class="action-menu" @keydown.esc.stop.prevent="closeOnEscape">
            <summary class="more-button" :aria-label="`${item.name}的更多操作`" title="更多操作">
              <svg viewBox="0 0 24 24" fill="currentColor" aria-hidden="true"><circle cx="5" cy="12" r="1.7"/><circle cx="12" cy="12" r="1.7"/><circle cx="19" cy="12" r="1.7"/></svg>
            </summary>
            <div class="menu-panel">
              <button v-if="item.type === 'FILE'" type="button" @click="choose($event, item, 'download')"><span aria-hidden="true">↓</span>下载</button>
              <button type="button" @click="choose($event, item, 'rename')"><span aria-hidden="true">✎</span>重命名</button>
              <button type="button" @click="choose($event, item, 'move')"><span aria-hidden="true">↗</span>移动</button>
              <button type="button" class="delete-action" @click="choose($event, item, 'delete')"><span aria-hidden="true">×</span>删除</button>
            </div>
          </details>
        </div>
      </li>
    </ul>

    <div v-else class="empty-state" role="status">
      <div class="empty-icon" aria-hidden="true"><svg viewBox="0 0 56 56" fill="none"><path d="M9 18a4 4 0 0 1 4-4h10l4 4h16a4 4 0 0 1 4 4v17a5 5 0 0 1-5 5H14a5 5 0 0 1-5-5V18Z" fill="#e6eef8"/><path d="M10 23h36v16a4 4 0 0 1-4 4H14a4 4 0 0 1-4-4V23Z" fill="#b9cde5"/><path d="M21 32h14" stroke="#fff" stroke-width="2" stroke-linecap="round"/></svg></div>
      <p class="empty-title">这里还没有文件</p>
      <p class="empty-description">试试上传文件，或检查搜索内容。</p>
    </div>
  </div>
</template>

<style scoped>
.file-list{width:100%;color:#253a50}.list-header,.file-row{display:grid;grid-template-columns:minmax(0,1fr) 160px 110px 58px;align-items:center;gap:20px}.list-header{padding:13px 20px;color:#99a6b5;font-size:12px;font-weight:500;letter-spacing:.02em;border-bottom:1px solid #edf1f6}.list-header span:last-child{text-align:center}.list-body{margin:0;padding:0;list-style:none}.file-row{position:relative;min-height:72px;padding:11px 20px;border-bottom:1px solid #f0f3f7;transition:background .16s}.file-row:hover,.file-row:focus-within{background:#f8fafd}.file-row:has(details[open]){z-index:3}.name-cell{display:flex;align-items:center;min-width:0;gap:14px}.file-icon{flex:none;display:grid;place-items:center;width:42px;height:42px;border-radius:12px}.folder-icon{background:#fdf4e3;color:#daa53f}.folder-icon svg{width:27px;height:27px}.document-icon{background:#edf3fc;color:#668abc;font-size:8px;font-weight:800;letter-spacing:.03em}.document-icon span{display:grid;place-items:center;width:25px;height:29px;background:white;border:1px solid #c8d9ef;border-radius:4px}.tag-pdf{background:#fdf0ef;color:#cf746e}.tag-pdf span{border-color:#f0c7c4}.tag-img{background:#edf7f5;color:#5c9d8c}.tag-img span{border-color:#c4e4dc}.tag-xls{background:#eff7f0;color:#63a475}.tag-xls span{border-color:#c8e4cd}.name-copy{min-width:0;display:flex;flex-direction:column;gap:4px}.folder-name,.file-name{display:block;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;color:#334860;font:inherit;font-size:14px;font-weight:600}.folder-name{max-width:100%;padding:0;border:0;background:none;text-align:left;cursor:pointer}.folder-name:hover{color:#315f9e;text-decoration:underline}.folder-name:focus-visible,.more-button:focus-visible,.menu-panel button:focus-visible{outline:2px solid #6597d5;outline-offset:3px}.date-cell,.size-cell{font-size:13px;color:#8c9aab;font-variant-numeric:tabular-nums}.action-cell{display:flex;justify-content:center}.action-menu{position:relative}.more-button{width:34px;height:34px;display:grid;place-items:center;border-radius:8px;cursor:pointer;color:#899aab;list-style:none}.more-button::-webkit-details-marker{display:none}.more-button svg{width:19px;height:19px}.more-button:hover,.action-menu[open] .more-button{background:#e9eff7;color:#3f648f}.menu-panel{position:absolute;z-index:4;right:0;top:38px;width:142px;padding:5px;border:1px solid #e5ebf3;border-radius:10px;background:#fff;box-shadow:0 12px 30px rgba(28,49,78,.14)}.menu-panel button{display:flex;align-items:center;gap:10px;width:100%;border:0;background:transparent;border-radius:6px;padding:9px 10px;color:#455a70;text-align:left;font:inherit;font-size:13px;cursor:pointer}.menu-panel button:hover{background:#f2f6fb}.menu-panel button span{width:15px;color:#879bae;text-align:center;font-size:16px}.menu-panel .delete-action,.menu-panel .delete-action span{color:#c65f5f}.mobile-meta{display:none}.empty-state{min-height:280px;display:flex;flex-direction:column;align-items:center;justify-content:center;padding:45px 20px;text-align:center}.empty-icon{width:72px;height:72px;display:grid;place-items:center;border-radius:24px;background:#f4f8fc}.empty-icon svg{width:56px;height:56px}.empty-title{margin:18px 0 0;color:#3c526c;font-size:15px;font-weight:650}.empty-description{margin:8px 0 0;color:#9ba9b8;font-size:13px}
@media(max-width:700px){.list-header{display:none}.file-row{grid-template-columns:minmax(0,1fr) 40px;gap:8px;padding:12px 14px}.date-cell,.size-cell{display:none}.mobile-meta{display:block;color:#a0adba;font-size:11px}.name-cell{gap:11px}.file-icon{width:39px;height:39px;border-radius:10px}}
@media(prefers-reduced-motion:reduce){.file-row{transition:none}}
</style>
