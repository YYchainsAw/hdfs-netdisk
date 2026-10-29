<script setup lang="ts">
import { computed, nextTick, ref, shallowRef } from 'vue'
import FileList from './FileList.vue'
import ActionDialog from './ActionDialog.vue'
import { useDemoDrive } from '../useDemoDrive'
import type { DiskNode } from '../types'

defineEmits<{ exit: [] }>()
const { nodes, search, visibleNodes, breadcrumbs, enter, createFolder, rename, move, remove, saveFile } = useDemoDrive()
type Mode = 'create' | 'rename' | 'move' | 'delete' | 'conflict'
type Task = { id: number; file: File; status: '等待处理' | '待选择' | '演示已添加' | '已跳过' | '失败'; note?: string }
const modal = shallowRef<Mode | null>(null)
const target = shallowRef<DiskNode | null>(null)
const error = shallowRef('')
const input = ref<HTMLInputElement | null>(null)
const tasks = ref<Task[]>([])
const panelOpen = shallowRef(false)
const activeTask = shallowRef<Task | null>(null)
const toast = shallowRef('')
const dragActive = shallowRef(false)
const folders = computed(() => nodes.value.filter(n => n.type === 'FOLDER'))
const completed = computed(() => tasks.value.filter(t => t.status === '演示已添加').length)
let nextTaskId = 1
let toastTimer: ReturnType<typeof setTimeout> | undefined
let returnFocus: HTMLElement | null = null

function notify(message: string) { toast.value = message; clearTimeout(toastTimer); toastTimer = setTimeout(() => { toast.value = '' }, 3500) }
function showDialog(mode: Mode, node: DiskNode | null = null) { returnFocus = document.activeElement instanceof HTMLElement ? document.activeElement : null; target.value = node; error.value = ''; modal.value = mode }
function closeDialog() { modal.value = null; error.value = ''; nextTick(() => returnFocus?.focus()) }
function open(node: DiskNode) { if (node.type === 'FOLDER') enter(node.id) }
function act(node: DiskNode, kind: 'download' | 'rename' | 'move' | 'delete') {
  if (kind !== 'download') { showDialog(kind, node); return }
  if (!node.data) { notify('这个演示文件没有可下载的内容'); return }
  const url = URL.createObjectURL(node.data)
  const link = document.createElement('a'); link.href = url; link.download = node.name; link.click()
  setTimeout(() => URL.revokeObjectURL(url), 1000)
  notify('已下载演示文件')
}
function perform(value: string) {
  if (modal.value === 'conflict') { resolveConflict(value); return }
  let message: string | null = null
  if (modal.value === 'create') message = createFolder(value)
  else if (target.value && modal.value === 'rename') message = rename(target.value.id, value)
  else if (target.value && modal.value === 'move') message = move(target.value.id, value)
  else if (target.value && modal.value === 'delete') message = remove(target.value.id)
  if (message) { error.value = message; return }
  const kind = modal.value
  closeDialog()
  notify(kind === 'create' ? '文件夹已添加到演示列表' : kind === 'delete' ? '已从演示列表删除' : kind === 'move' ? '已移动演示文件' : '已修改名称')
}
function onFiles(files: FileList | null) {
  if (!files?.length) return
  for (const file of Array.from(files)) tasks.value.push({ id: nextTaskId++, file, status: '等待处理' })
  if (input.value) input.value.value = ''
  panelOpen.value = true
  processNext()
}
function processNext() {
  if (activeTask.value || modal.value) return
  const task = tasks.value.find(t => t.status === '等待处理')
  if (!task) return
  const result = saveFile(task.file)
  if (result.status === 'conflict') { task.status = '待选择'; activeTask.value = task; showDialog('conflict'); return }
  task.status = result.status === 'added' ? '演示已添加' : result.status === 'skipped' ? '已跳过' : '失败'
  task.note = result.status === 'error' ? result.message : result.name
  processNext()
}
function resolveConflict(choice: string) {
  const task = activeTask.value
  if (!task || (choice !== 'keep' && choice !== 'skip')) return
  const result = saveFile(task.file, choice)
  task.status = result.status === 'added' ? '演示已添加' : result.status === 'skipped' ? '已跳过' : '失败'
  task.note = result.status === 'error' ? result.message : result.name
  activeTask.value = null; closeDialog(); processNext()
}
function cancelDialog() { if (modal.value === 'conflict') resolveConflict('skip'); else { closeDialog(); processNext() } }
function drop(event: DragEvent) { dragActive.value = false; onFiles(event.dataTransfer?.files ?? null) }
</script>

<template>
  <div class="workspace">
    <aside class="sidebar">
      <div class="logo"><span class="logo-mark"><svg viewBox="0 0 32 32" fill="none" aria-hidden="true"><path d="M5 20.5A5.5 5.5 0 0 1 10.5 15h1.2a6.7 6.7 0 0 1 12.8 1.8A4.2 4.2 0 0 1 24 25H10a5 5 0 0 1-5-4.5Z" stroke="currentColor" stroke-width="2"/><path d="M12 19h8M14 22h4" stroke="currentColor" stroke-width="2" stroke-linecap="round"/></svg></span><span><b>云屿</b><small>个人文件空间</small></span></div>
      <div class="nav-label">WORKSPACE</div>
      <button class="nav-item active" @click="enter('0')"><svg viewBox="0 0 24 24" fill="none" aria-hidden="true"><path d="M3 7a2 2 0 0 1 2-2h5l2 2h7a2 2 0 0 1 2 2v9a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V7Z" stroke="currentColor" stroke-width="1.7"/></svg>我的文件 <span>›</span></button>
      <div class="sidebar-bottom"><div class="side-note"><span class="side-dot"></span><b>演示空间</b><p>当前内容仅保留在本页面，刷新后将重置。</p></div><button class="back-button" @click="$emit('exit')">← 返回欢迎页</button></div>
    </aside>
    <div class="main-column">
      <header class="topbar"><span class="top-location">个人空间 <span>/</span> 我的文件</span><div class="top-right"><span class="demo-badge"><i></i>体验模式</span><span class="avatar">访</span><span class="user-name">访客</span><button class="mobile-exit" @click="$emit('exit')">退出</button></div></header>
      <main class="content" @dragover.prevent="dragActive = true" @dragleave.prevent="dragActive = false" @drop.prevent="drop">
        <div class="intro-row"><div><div class="eyebrow">YOUR PERSONAL SPACE</div><h1>我的文件<span class="title-dot">.</span></h1><p>把每一份重要，都放在合适的位置。</p></div><div class="top-actions"><button class="secondary" @click="showDialog('create')"><span>＋</span> 新建文件夹</button><button class="primary" @click="input?.click()"><span>↑</span> 上传文件</button><input ref="input" type="file" multiple hidden @change="onFiles(($event.target as HTMLInputElement).files)" /></div></div>
        <div class="demo-strip"><span class="info-icon">i</span><span><strong>当前为交互演示</strong> · 文件只在当前页面中模拟，未上传到服务器或 HDFS。</span><span class="strip-label">DEMO</span></div>
        <section class="files-card" aria-label="文件列表">
          <div class="card-header"><div><div class="card-title">文件空间 <span>{{ visibleNodes.length }}</span></div><nav class="crumbs" aria-label="当前路径"><template v-for="(crumb,index) in breadcrumbs" :key="crumb.id"><span v-if="index" class="crumb-separator">/</span><button @click="enter(crumb.id)">{{ crumb.name }}</button></template></nav></div><label class="search"><svg viewBox="0 0 24 24" fill="none" aria-hidden="true"><circle cx="10.5" cy="10.5" r="6.5" stroke="currentColor" stroke-width="1.7"/><path d="m15.5 15.5 4 4" stroke="currentColor" stroke-width="1.7" stroke-linecap="round"/></svg><input v-model="search" type="search" placeholder="搜索当前文件夹" aria-label="搜索当前文件夹" /><span>⌕</span></label></div>
          <FileList :items="visibleNodes" @open="open" @action="act" />
          <div class="card-footer"><span>共 {{ visibleNodes.length }} 项</span><span>演示数据 · 仅当前页面可见</span></div>
        </section>
        <div class="below-card"><span>YOUR FILES, YOUR SPACE</span><span>让整理变得简单一点 ✦</span></div>
        <div v-if="dragActive" class="drop-overlay">松开鼠标，将文件加入演示列表</div>
      </main>
    </div>
    <div v-if="tasks.length" class="transfer"><button class="transfer-head" @click="panelOpen = !panelOpen" :aria-expanded="panelOpen"><span class="transfer-icon">↑</span><span class="transfer-title">上传任务<small>{{ completed }} / {{ tasks.length }} 项演示已添加</small></span><span>{{ panelOpen ? '⌄' : '⌃' }}</span></button><div v-if="panelOpen" class="transfer-body"><div class="transfer-notice">文件不会发送到服务器</div><div v-for="task in tasks" :key="task.id" class="transfer-row"><span class="transfer-file">{{ task.file.name }}<small v-if="task.note && task.note !== task.file.name">{{ task.note }}</small></span><span :class="['transfer-status', { good: task.status === '演示已添加', failed: task.status === '失败' }]">{{ task.status }}</span></div></div></div>
    <div v-if="toast" class="toast" role="status">{{ toast }}</div>
    <ActionDialog v-if="modal" :mode="modal" :target="target" :folders="folders" :error="error" :file-name="activeTask?.file.name" @cancel="cancelDialog" @submit="perform" />
  </div>
</template>

<style scoped>
.workspace{min-height:100dvh;display:flex;background:#f4f7fb;color:#1f334d}.sidebar{width:228px;flex:none;background:#21354e;color:#e7eef8;min-height:100dvh;display:flex;flex-direction:column;padding:28px 16px 20px}.logo{display:flex;gap:11px;align-items:center;padding:0 7px}.logo-mark{width:39px;height:39px;background:#668ac0;border-radius:11px;display:grid;place-items:center}.logo-mark svg{width:28px}.logo b{font-size:18px;letter-spacing:.08em}.logo small{display:block;color:#a1b4ca;font-size:10px;margin-top:3px;letter-spacing:.08em}.nav-label{font-size:10px;color:#8399b2;letter-spacing:.16em;margin:59px 9px 13px}.nav-item{width:100%;border:0;background:transparent;color:#b7c7d9;display:flex;align-items:center;gap:12px;padding:13px;border-radius:9px;text-align:left;font-size:14px}.nav-item svg{width:19px}.nav-item span{margin-left:auto}.nav-item.active{background:#385474;color:#fff}.sidebar-bottom{margin-top:auto}.side-note{border:1px solid #52677f;border-radius:12px;padding:14px 13px;color:#d3e1ed;font-size:12px}.side-dot,.demo-badge i{display:inline-block;width:7px;height:7px;background:#87c2ad;border-radius:50%;margin-right:7px}.side-note p{font-size:11px;color:#a4b7c9;line-height:1.7;margin:9px 0 0}.back-button{background:none;border:0;color:#b7c7d9;font-size:12px;margin:22px 0 0;padding:4px}.main-column{min-width:0;flex:1}.topbar{height:70px;background:#fff;border-bottom:1px solid #e9eef5;display:flex;align-items:center;justify-content:space-between;padding:0 5.1%}.top-location{font-size:12px;color:#8999ab}.top-location span{padding:0 10px;color:#c2ccd6}.top-right{display:flex;align-items:center;gap:11px;font-size:12px;color:#536a82}.demo-badge{border:1px solid #dce7ef;background:#f8fbfb;border-radius:20px;padding:7px 10px;color:#6a8390;font-size:11px;margin-right:14px}.avatar{width:32px;height:32px;border-radius:50%;display:grid;place-items:center;background:#e7eef9;color:#5173a1;font-size:12px}.content{max-width:1460px;margin:auto;padding:56px 5.1% 72px;position:relative}.intro-row{display:flex;justify-content:space-between;align-items:flex-end;gap:20px}.eyebrow{color:#8ca0b5;font-size:10px;font-weight:700;letter-spacing:.16em}.intro-row h1{font-size:30px;letter-spacing:.03em;margin:12px 0 9px;color:#263e5d}.title-dot{color:#82a6d0}.intro-row p{font-size:13px;color:#94a3b4;margin:0}.top-actions{display:flex;gap:10px}.primary,.secondary{height:41px;padding:0 17px;border-radius:8px;font-size:13px;font-weight:600}.primary{background:#3c67a9;border:1px solid #3c67a9;color:#fff}.primary:hover{background:#315992}.secondary{border:1px solid #dce5f0;background:#fff;color:#4d6178}.secondary:hover{background:#f7faff}.primary span,.secondary span{font-size:17px;margin-right:5px}.demo-strip{display:flex;align-items:center;gap:11px;border:1px solid #e0eaf5;background:#edf4fb;color:#71869c;border-radius:9px;padding:12px 15px;margin:31px 0 18px;font-size:12px;line-height:1.5}.demo-strip strong{color:#4a6b8e}.info-icon{width:17px;height:17px;display:grid;place-items:center;border:1px solid #a2bcd5;border-radius:50%;color:#6789ab;font-family:serif;flex:none}.strip-label{margin-left:auto;font-size:10px;letter-spacing:.14em;color:#98aec4}.files-card{background:white;border:1px solid #e8edf4;border-radius:13px;box-shadow:0 10px 30px rgba(37,62,92,.035);overflow:hidden}.card-header{padding:24px 24px 19px;display:flex;align-items:center;justify-content:space-between;gap:20px}.card-title{font-size:15px;font-weight:700;color:#263d57}.card-title span{display:inline-block;background:#f0f4f9;color:#879bb0;font-weight:600;font-size:11px;border-radius:6px;padding:3px 7px;margin-left:7px}.crumbs{display:flex;flex-wrap:wrap;gap:9px;margin-top:9px;align-items:center}.crumbs button{border:0;padding:0;background:none;color:#708ca9;font-size:12px}.crumbs button:hover{color:#315d9b}.crumb-separator{color:#ccd7e4;font-size:12px}.search{width:235px;height:38px;display:flex;align-items:center;gap:9px;padding:0 12px;background:#f7f9fc;border:1px solid #edf1f6;border-radius:8px;color:#9bacc0}.search:focus-within{border-color:#a9c0df}.search svg{width:16px;flex:none}.search input{width:100%;min-width:0;border:0;outline:0;background:none;color:#405874;font-size:12px}.search input::placeholder{color:#a7b5c5}.search span{font-size:16px}.card-footer{padding:15px 24px;display:flex;justify-content:space-between;border-top:1px solid #f1f4f8;color:#a0adbb;font-size:11px}.below-card{display:flex;justify-content:space-between;color:#aebccc;letter-spacing:.08em;font-size:10px;margin:20px 2px}.drop-overlay{position:absolute;inset:10px;border:2px dashed #7e9fc9;background:rgba(235,244,255,.93);border-radius:16px;display:grid;place-items:center;pointer-events:none;color:#385f91;font-weight:600;font-size:16px}.transfer{position:fixed;bottom:0;right:30px;width:min(330px,calc(100vw - 24px));border:1px solid #dce6f1;border-bottom:0;border-radius:12px 12px 0 0;background:white;box-shadow:0 4px 35px #263e5d24;z-index:5}.transfer-head{width:100%;display:flex;align-items:center;gap:10px;background:#fff;border:0;border-radius:12px 12px 0 0;padding:13px 15px;text-align:left;color:#5a7088}.transfer-icon{background:#eaf1fb;color:#4770a6;width:32px;height:32px;display:grid;place-items:center;border-radius:9px;font-size:18px}.transfer-title{flex:1;font-weight:700;color:#35506b;font-size:13px}.transfer-title small{display:block;color:#9aacbd;font-size:10px;font-weight:400;margin-top:3px}.transfer-body{border-top:1px solid #edf1f6;max-height:240px;overflow:auto}.transfer-notice{font-size:11px;color:#8093a6;padding:12px 15px;background:#f8fafc}.transfer-row{display:flex;justify-content:space-between;gap:10px;padding:13px 15px;border-top:1px solid #f2f5f8;font-size:12px}.transfer-file{min-width:0;overflow-wrap:anywhere}.transfer-file small{display:block;color:#8c9db0;margin-top:3px}.transfer-status{white-space:nowrap;color:#a58a56;font-size:11px}.transfer-status.good{color:#578e77}.transfer-status.failed{color:#c46f65}.toast{position:fixed;bottom:25px;left:50%;transform:translateX(-50%);z-index:8;background:#263e59;color:#fff;font-size:13px;border-radius:9px;padding:11px 16px;box-shadow:0 8px 25px #283f5d33}
@media(max-width:820px){.sidebar{width:70px;padding:24px 10px}.logo{padding:0;justify-content:center}.logo>span:last-child,.nav-label,.nav-item span,.side-note,.back-button{display:none}.nav-item{font-size:0;justify-content:center;padding:12px;margin-top:55px}.nav-item svg{width:21px}.content{padding:38px 4% 70px}.topbar{padding:0 4%}.intro-row h1{font-size:27px}}
@media(max-width:560px){.sidebar{display:none}.topbar{height:60px;padding:0 18px}.top-location{font-size:11px}.user-name{display:none}.demo-badge{margin-right:0}.content{padding:31px 16px 90px}.intro-row{align-items:flex-start;flex-direction:column;gap:20px}.top-actions{width:100%}.top-actions button{flex:1}.demo-strip{margin:22px 0 14px}.strip-label{display:none}.card-header{padding:20px 16px 15px;flex-direction:column;align-items:stretch}.search{width:100%}.card-footer{padding:13px 16px}.card-footer span:last-child,.below-card{display:none}.transfer{right:12px}}
@media(prefers-reduced-motion:reduce){*,*::before,*::after{transition:none!important;animation:none!important}}
.files-card{overflow:visible}.mobile-exit{display:none;border:0;background:none;color:#617995;font-size:12px;padding:6px}
@media(max-width:560px){.mobile-exit{display:block}}
</style>
