<script setup lang="ts">
import { computed, nextTick, onMounted, ref, useTemplateRef } from 'vue'
import type { DiskNode } from '../types'

type Mode = 'create' | 'rename' | 'move' | 'delete' | 'conflict'
const props = defineProps<{
  mode: Mode
  target: DiskNode | null
  fileName?: string
  folders: DiskNode[]
  error: string
}>()
const emit = defineEmits<{ cancel: []; submit: [value: string] }>()

const dialog = useTemplateRef<HTMLDialogElement>('dialog')
const field = useTemplateRef<HTMLInputElement>('field')
const select = useTemplateRef<HTMLSelectElement>('select')
const firstButton = useTemplateRef<HTMLButtonElement>('firstButton')
const name = ref(props.mode === 'rename' ? props.target?.name ?? '' : '')
const folderId = ref(props.target?.parentId ?? '0')

const title = computed(() => ({
  create: '新建文件夹',
  rename: '重命名',
  move: '移动到',
  delete: '确认删除',
  conflict: '发现同名文件',
})[props.mode])

function folderPath(folder: DiskNode): string {
  const names = [folder.name]
  let parentId = folder.parentId
  const visited = new Set([folder.id])
  while (parentId !== '0' && !visited.has(parentId)) {
    visited.add(parentId)
    const parent = props.folders.find((item) => item.id === parentId)
    if (!parent) break
    names.unshift(parent.name)
    parentId = parent.parentId
  }
  return `我的文件 / ${names.join(' / ')}`
}

function submit() {
  if (props.mode === 'move') emit('submit', folderId.value)
  else if (props.mode === 'delete') emit('submit', 'delete')
  else emit('submit', name.value)
}

onMounted(async () => {
  dialog.value?.showModal()
  await nextTick()
  if (props.mode === 'create' || props.mode === 'rename') {
    field.value?.focus()
    if (props.mode === 'rename') field.value?.select()
  } else if (props.mode === 'move') select.value?.focus()
  else firstButton.value?.focus()
})
</script>

<template>
  <dialog ref="dialog" class="action-dialog" aria-labelledby="action-title" @cancel.prevent="emit('cancel')">
    <form class="dialog-content" @submit.prevent="submit">
      <div class="dialog-topline">
        <div class="dialog-icon" aria-hidden="true">
          <svg v-if="mode === 'delete'" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7"><path d="M4 7h16M9 7V4h6v3m-9 0 1 13h10l1-13M10 11v5m4-5v5" stroke-linecap="round" stroke-linejoin="round" /></svg>
          <svg v-else-if="mode === 'conflict'" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7"><path d="M12 3 2.8 19h18.4L12 3Zm0 6v4m0 3h.01" stroke-linecap="round" stroke-linejoin="round" /></svg>
          <svg v-else viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7"><path d="M3.5 6.5h6l2 2H20.5v10h-17v-12Z" stroke-linecap="round" stroke-linejoin="round" /></svg>
        </div>
        <button type="button" class="icon-button" aria-label="关闭对话框" @click="emit('cancel')">×</button>
      </div>

      <h2 id="action-title" class="dialog-title">{{ title }}</h2>

      <template v-if="mode === 'create' || mode === 'rename'">
        <p class="dialog-description">{{ mode === 'create' ? '为新文件夹取一个名字。' : '输入新的名称，便于以后查找。' }}</p>
        <label class="field-label" for="node-name">{{ mode === 'create' ? '文件夹名称' : '新名称' }}</label>
        <input id="node-name" ref="field" v-model="name" class="text-field" maxlength="191" :aria-invalid="!!error" :aria-describedby="error ? 'dialog-error' : undefined" autocomplete="off" :placeholder="mode === 'create' ? '例如：项目资料' : ''" />
      </template>

      <template v-else-if="mode === 'move'">
        <p class="dialog-description">选择「{{ target?.name }}」的新位置。</p>
        <label class="field-label" for="folder-target">目标文件夹</label>
        <select id="folder-target" ref="select" v-model="folderId" class="text-field select-field" :aria-invalid="!!error" :aria-describedby="error ? 'dialog-error' : undefined">
          <option value="0">我的文件</option>
          <option v-for="folder in folders" :key="folder.id" :value="folder.id">{{ folderPath(folder) }}</option>
        </select>
      </template>

      <p v-else-if="mode === 'delete'" class="dialog-description">将删除「{{ target?.name }}」。演示中的删除无法撤销；非空文件夹请先清空。</p>
      <p v-else class="dialog-description">当前文件夹中已有「{{ fileName }}」。你可以保留两份，也可以跳过本次上传。</p>

      <p v-if="error" id="dialog-error" class="error-message" role="alert">{{ error }}</p>

      <div v-if="mode === 'conflict'" class="dialog-actions">
        <button ref="firstButton" type="button" class="button button-secondary" @click="emit('submit', 'skip')">跳过</button>
        <button type="button" class="button button-primary" @click="emit('submit', 'keep')">保留两份</button>
      </div>
      <div v-else class="dialog-actions">
        <button ref="firstButton" type="button" class="button button-secondary" @click="emit('cancel')">取消</button>
        <button type="submit" class="button" :class="mode === 'delete' ? 'button-danger' : 'button-primary'">{{ mode === 'create' ? '创建文件夹' : mode === 'rename' ? '保存名称' : mode === 'move' ? '移动到此处' : '确认删除' }}</button>
      </div>
    </form>
  </dialog>
</template>

<style scoped>
.action-dialog { width: min(440px, calc(100vw - 32px)); max-width: none; padding: 0; border: 1px solid #dfe6ef; border-radius: 20px; color: #20344d; background: #fff; box-shadow: 0 30px 80px rgb(20 37 61 / 22%); }
.action-dialog::backdrop { background: rgb(19 33 52 / 48%); }
.dialog-content { padding: 25px; }
.dialog-topline { display: flex; justify-content: space-between; align-items: flex-start; }
.dialog-icon { display: grid; place-items: center; width: 43px; height: 43px; color: #4165b4; background: #edf3ff; border-radius: 13px; }
.dialog-icon svg { width: 22px; height: 22px; }
.icon-button { display: grid; place-items: center; width: 34px; height: 34px; padding: 0; border: 0; border-radius: 9px; background: transparent; color: #78879a; font-size: 25px; line-height: 1; cursor: pointer; }
.icon-button:hover { background: #f1f4f8; color: #2e4057; }
.dialog-title { margin: 20px 0 8px; font-size: 21px; font-weight: 700; letter-spacing: -.025em; }
.dialog-description { margin: 0 0 21px; color: #6d7d90; font-size: 14px; line-height: 1.65; overflow-wrap: anywhere; }
.field-label { display: block; margin-bottom: 8px; font-size: 13px; font-weight: 650; color: #465b72; }
.text-field { box-sizing: border-box; width: 100%; height: 44px; padding: 0 12px; border: 1px solid #d9e2ed; border-radius: 10px; color: #20344d; background: #fbfcfe; font: inherit; font-size: 14px; }
.select-field { cursor: pointer; }
.text-field:focus { outline: 3px solid #dbe7ff; border-color: #5276cb; }
.error-message { margin: 9px 0 0; color: #b64646; font-size: 13px; line-height: 1.5; }
.dialog-actions { display: flex; justify-content: flex-end; gap: 9px; margin-top: 26px; }
.button { min-height: 40px; padding: 0 16px; border: 1px solid transparent; border-radius: 10px; font: inherit; font-size: 13px; font-weight: 650; cursor: pointer; }
.button-secondary { color: #53677c; background: #f3f6f9; border-color: #e6ebf1; }
.button-secondary:hover { background: #e9eff5; }
.button-primary { color: #fff; background: #4669c7; }
.button-primary:hover { background: #385bb9; }
.button-danger { color: #fff; background: #c45252; }
.button-danger:hover { background: #ac4141; }
.button:focus-visible, .icon-button:focus-visible { outline: 3px solid #a7bff6; outline-offset: 2px; }
@media (max-width: 480px) { .dialog-content { padding: 22px; } .dialog-actions .button { flex: 1; } }
</style>
