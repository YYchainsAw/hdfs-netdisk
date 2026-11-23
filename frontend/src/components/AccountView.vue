<script setup lang="ts">
import { nextTick, shallowRef } from 'vue'
import type { AuthUser } from '../composables/useAuth'
import { useNodes, type NodeItem } from '../composables/useNodes'
import RealFileList from './RealFileList.vue'
import FolderDialog from './FolderDialog.vue'
import UploadPanel from './UploadPanel.vue'
import NodeActionDialog from './NodeActionDialog.vue'
import PasswordDialog from './PasswordDialog.vue'
import UsageSummary from './UsageSummary.vue'
import BatchManager from './BatchManager.vue'
import PreviewDialog from './PreviewDialog.vue'
import ShareDialog from './ShareDialog.vue'
import TrashView from './TrashView.vue'
defineProps<{ user: AuthUser; busy: boolean; error: string }>()
const emit = defineEmits<{ logout: []; expired: []; 'password-changed': [] }>()
const { path, items, keyword, sort, loading, saving, error: listError, dialogError, page, hasMore, load, go, open, create, mutate } = useNodes(() => emit('expired'))
const view = shallowRef<'files' | 'trash'>('files')
const dialog = shallowRef(false)
const passwordDialog = shallowRef(false)
const preview = shallowRef<NodeItem | null>(null)
const shareNode = shallowRef<NodeItem | null>(null)
const action = shallowRef<{node: NodeItem; mode: 'rename' | 'delete'} | null>(null)
const usageRefresh = shallowRef(0)
const notice = shallowRef('')
let returnFocus: HTMLElement | null = null
function showDialog() { returnFocus = document.activeElement instanceof HTMLElement ? document.activeElement : null; dialogError.value = ''; dialog.value = true }
function closeDialog() { if (saving.value) return; dialog.value = false; void nextTick(() => returnFocus?.focus()) }
async function createFolder(name: string) { if (await create(name)) { dialog.value = false; notice.value = '文件夹已创建'; void nextTick(() => returnFocus?.focus()) } }
function search() { notice.value = ''; void load() }
function showPreview(node: NodeItem) { returnFocus = document.activeElement instanceof HTMLElement ? document.activeElement : null; preview.value = node }
function closePreview() { preview.value = null; void nextTick(() => returnFocus?.focus()) }
function showPassword() { returnFocus = document.activeElement instanceof HTMLElement ? document.activeElement : null; passwordDialog.value = true }
function closePassword() { passwordDialog.value = false; void nextTick(() => returnFocus?.focus()) }
function showAction(node: NodeItem, mode: 'rename' | 'delete') { returnFocus = document.activeElement instanceof HTMLElement ? document.activeElement : null; dialogError.value = ''; action.value = {node,mode} }
function closeAction() { if (saving.value) return; action.value = null; void nextTick(() => returnFocus?.focus()) }
async function doAction(name?: string) {
  if (!action.value) return
  const current = action.value
  if (await mutate(current.node,current.mode,name)) {
    action.value = null
    notice.value = current.mode === 'delete' ? '已移入回收站。' : '名称已修改。'
    if (current.mode === 'delete') usageRefresh.value++
    void nextTick(() => returnFocus?.focus())
  }
}
</script>
<template>
  <div class="workspace">
    <aside class="sidebar"><div class="logo"><span class="logo-mark">☁</span><span><b>云屿</b><small>个人文件空间</small></span></div><div class="nav-label">WORKSPACE</div><button class="nav-item" :class="{selected: view === 'files'}" @click="view = 'files'; go(0)"><span class="folder-symbol">▱</span>我的文件 <span class="arrow">›</span></button><button class="nav-item" :class="{selected: view === 'trash'}" @click="view = 'trash'"><span class="folder-symbol">♲</span>回收站 <span class="arrow">›</span></button><div class="side-note"><b><i></i>文件空间</b><p>按文件夹整理，让重要的资料都有位置。</p></div></aside>
    <div class="main-column"><header class="topbar"><span>个人空间 <span class="slash">/</span> {{ view === 'files' ? '我的文件' : '回收站' }}</span><div class="top-right"><span class="avatar">{{ user.username.slice(0,1).toUpperCase() }}</span><span class="username">{{ user.username }}</span><button class="logout" :disabled="busy" @click="showPassword">修改密码</button><button class="logout" :disabled="busy" @click="$emit('logout')">{{ busy ? '退出中…' : '退出登录' }}</button></div></header>
      <main class="content"><nav class="mobile-nav" aria-label="空间导航"><button :aria-current="view === 'files' ? 'page' : undefined" @click="view = 'files'">我的文件</button><button :aria-current="view === 'trash' ? 'page' : undefined" @click="view = 'trash'">回收站</button></nav><div v-show="view === 'files'"><div class="intro"><div><span class="eyebrow">YOUR PERSONAL SPACE</span><h1>我的文件<span>.</span></h1><p>把每一份重要，都放在合适的位置。</p></div><div class="actions"><button class="secondary" @click="showDialog">＋ 新建文件夹</button></div></div>
        <UploadPanel :user-id="user.id" :parent-id="path[path.length - 1]!.id" @expired="emit('expired')" @uploaded="id => { usageRefresh++; if (id === path[path.length - 1]!.id) void load() }" />
        <UsageSummary :refresh="usageRefresh" @expired="emit('expired')" /><BatchManager :items="items" :parent-id="path[path.length - 1]!.id" :busy="saving || loading" @expired="emit('expired')" @changed="() => { usageRefresh++; void load(page) }" /><section class="files" aria-label="文件列表"><div class="card-head"><div><div class="card-title">文件空间</div><nav class="crumbs" aria-label="当前路径"><template v-for="(crumb,index) in path" :key="crumb.id"><span v-if="index">/</span><button :disabled="loading || saving" @click="go(index)">{{ crumb.name }}</button></template></nav></div><label class="sort-control">排序<select v-model="sort" :disabled="loading" aria-label="文件排序方式" @change="load()"><option value="name">名称</option><option value="time">上传时间</option><option value="size">文件大小</option></select></label><form class="search" @submit.prevent="search"><span aria-hidden="true">⌕</span><input v-model="keyword" type="search" aria-label="搜索当前文件夹" placeholder="搜索当前文件夹" /><button type="submit" :disabled="loading">搜索</button></form></div>
          <div v-if="listError" class="list-error" role="alert">{{ listError }} <button @click="load(page)">重试</button></div><RealFileList v-else :items="items" :loading="loading" :keyword="keyword" @open="open" @preview="showPreview" @share="node => shareNode = node" @action="showAction" />
          <footer class="footer"><span>第 {{ page }} 页 · {{ items.length }} 项</span><div class="pagination"><button :disabled="page <= 1 || loading" @click="load(page - 1)">上一页</button><button :disabled="!hasMore || loading" @click="load(page + 1)">下一页</button></div></footer>
        </section><p v-if="notice" class="notice" role="status">{{ notice }}</p><p v-if="error" class="error" role="alert">{{ error }}</p><div class="below">YOUR FILES, YOUR SPACE <span>让整理变得简单一点 ✦</span></div></div>
        <TrashView v-if="view === 'trash'" @expired="emit('expired')" @changed="usageRefresh++; void load()" />
      </main></div><PreviewDialog v-if="preview" :item="preview" @close="closePreview" @expired="emit('expired')" /><FolderDialog v-if="dialog" :busy="saving" :error="dialogError" @cancel="closeDialog" @create="createFolder" /><NodeActionDialog v-if="action" :node="action.node" :mode="action.mode" :busy="saving" :error="dialogError" @cancel="closeAction" @submit="doAction" /><ShareDialog v-if="shareNode" :node-id="shareNode.id" :name="shareNode.name" @close="shareNode = null" /><PasswordDialog v-if="passwordDialog" @cancel="closePassword" @expired="emit('expired')" @changed="emit('password-changed')" />

  </div>
</template>
<style scoped>
.workspace{min-height:100dvh;display:flex;background:#f4f7fb;color:#1f334d}.sidebar{width:228px;flex:none;min-height:100dvh;display:flex;flex-direction:column;background:#21354e;color:#e7eef8;padding:28px 16px}.logo{display:flex;align-items:center;gap:11px;padding:0 7px}.logo-mark{width:39px;height:39px;background:#668ac0;border-radius:11px;display:grid;place-items:center;font-size:22px}.logo b{font-size:18px;letter-spacing:.08em}.logo small{display:block;color:#a1b4ca;font-size:10px;margin-top:3px}.nav-label{font-size:10px;color:#8399b2;letter-spacing:.16em;margin:59px 9px 13px}.nav-item{width:100%;border:0;background:transparent;color:white;display:flex;align-items:center;gap:12px;padding:13px;border-radius:9px;text-align:left;font-size:14px}.nav-item.selected{background:#385474}.mobile-nav{display:none}.folder-symbol{font-size:22px;line-height:16px}.arrow{margin-left:auto}.side-note{margin-top:auto;border:1px solid #52677f;border-radius:12px;padding:14px 13px;color:#d3e1ed;font-size:12px}.side-note i{display:inline-block;width:7px;height:7px;background:#87c2ad;border-radius:50%;margin-right:7px}.side-note p{color:#a4b7c9;font-size:11px;line-height:1.7;margin:9px 0 0}.main-column{flex:1;min-width:0}.topbar{height:70px;padding:0 5.1%;display:flex;align-items:center;justify-content:space-between;border-bottom:1px solid #e9eef5;background:white;color:#8999ab;font-size:12px}.slash{padding:0 10px;color:#c2ccd6}.top-right{display:flex;align-items:center;gap:11px}.avatar{width:32px;height:32px;border-radius:50%;display:grid;place-items:center;background:#e7eef9;color:#5173a1}.username{max-width:130px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;color:#536a82}.logout{border:1px solid #dce5ef;background:#fff;padding:8px 11px;border-radius:8px;color:#55718b;font-size:12px}.content{max-width:1460px;margin:auto;padding:56px 5.1% 72px}.intro{display:flex;align-items:flex-end;justify-content:space-between;gap:20px}.eyebrow{color:#8ca0b5;font-size:10px;font-weight:700;letter-spacing:.16em}.intro h1{font-size:30px;letter-spacing:.03em;margin:12px 0 9px;color:#263e5d}.intro h1 span{color:#82a6d0}.intro p{font-size:13px;color:#94a3b4;margin:0}.actions{display:flex;gap:10px}.actions button{height:41px;padding:0 17px;border-radius:8px;font-size:13px;font-weight:600}.primary{background:#3c67a9;border:1px solid #3c67a9;color:#fff}.secondary{border:1px solid #dce5f0;background:#fff;color:#4d6178}.secondary:hover{background:#f7faff}.info{display:flex;align-items:center;gap:11px;border:1px solid #e0eaf5;background:#edf4fb;color:#71869c;border-radius:9px;padding:12px 15px;margin:31px 0 18px;font-size:12px;line-height:1.5}.info span{width:17px;height:17px;text-align:center;border:1px solid #a2bcd5;border-radius:50%;font-family:serif;flex:none}.files{background:white;border:1px solid #e8edf4;border-radius:13px;box-shadow:0 10px 30px #253e5c09;overflow:hidden}.card-head{padding:24px 24px 19px;display:flex;align-items:center;justify-content:space-between;gap:20px}.card-title{font-size:15px;font-weight:700;color:#263d57}.crumbs{display:flex;flex-wrap:wrap;gap:9px;margin-top:9px;align-items:center}.crumbs button{border:0;background:none;padding:0;color:#708ca9;font-size:12px}.crumbs span{color:#ccd7e4}.sort-control{display:flex;align-items:center;gap:7px;color:#71839a;font-size:12px;white-space:nowrap}.sort-control select{border:1px solid #dce5ef;background:#fff;color:#405874;padding:8px;border-radius:8px;font:inherit}.search{height:38px;width:260px;display:flex;align-items:center;gap:8px;padding:0 10px;background:#f7f9fc;border:1px solid #edf1f6;border-radius:8px;color:#9bacc0}.search input{min-width:0;flex:1;border:0;outline:0;background:transparent;font-size:12px;color:#405874}.search button{border:0;background:transparent;color:#6684a5;font-size:12px}.footer{padding:15px 24px;display:flex;justify-content:space-between;align-items:center;color:#a0adbb;font-size:11px}.pagination{display:flex;gap:12px}.pagination button,.list-error button{border:0;background:none;color:#6786a8;font-size:12px}.list-error{padding:40px;text-align:center;color:#b34f4c;font-size:13px}.notice{color:#47856b;font-size:13px}.error{color:#b34f4c;font-size:13px}.below{display:flex;justify-content:space-between;color:#aebccc;letter-spacing:.08em;font-size:10px;margin:20px 2px}@media(max-width:820px){.sidebar{width:70px;padding:24px 10px}.logo{padding:0;justify-content:center}.logo>span:last-child,.nav-label,.nav-item .arrow,.side-note{display:none}.nav-item{font-size:0;justify-content:center;padding:12px}.nav-label+.nav-item{margin-top:55px}.content{padding:38px 4% 70px}.topbar{padding:0 4%}}@media(max-width:560px){.sidebar{display:none}.mobile-nav{display:flex;gap:20px;margin-bottom:24px}.mobile-nav button{border:0;border-bottom:2px solid transparent;background:none;color:#6d829b;padding:8px 0}.mobile-nav button[aria-current="page"]{color:#315f9e;border-color:#315f9e;font-weight:700}.topbar{height:60px;padding:0 18px}.content{padding:31px 16px 90px}.intro{align-items:flex-start;flex-direction:column}.actions{width:100%}.actions button{flex:1}.info{margin:22px 0 14px}.card-head{padding:20px 16px 15px;flex-direction:column;align-items:stretch}.search{width:100%}.footer{padding:13px 16px}.below{display:none}.username{display:none}}
</style>
