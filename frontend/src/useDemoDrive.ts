import { computed, ref, shallowRef } from 'vue'
import type { Breadcrumb, DiskNode, SaveFileResult } from './types'

const MAX_NAME = 191
let nextId = 10

function validateName(value: string): { name: string; error: string | null } {
  const name = value.normalize('NFC')
  if (!name || [...name].length > MAX_NAME || name === '.' || name === '..') {
    return { name, error: '名称须为 1–191 个字符，不能是 . 或 ..' }
  }
  if (/[\/\x00-\x1f\x7f-\x9f]/u.test(name)) {
    return { name, error: '名称不能包含斜杠或控制字符' }
  }
  return { name, error: null }
}

function sampleFile(name: string, text: string, parentId = '0'): DiskNode {
  const data = new Blob([text], { type: 'text/plain;charset=utf-8' })
  return { id: String(nextId++), parentId, name, type: 'FILE', size: data.size, modified: '2026-10-29T08:30:00+08:00', data }
}

function initialNodes(): DiskNode[] {
  nextId = 10
  return [
    { id: '1', parentId: '0', name: '项目资料', type: 'FOLDER', size: null, modified: '2026-10-29T09:00:00+08:00' },
    { id: '2', parentId: '0', name: '学习笔记', type: 'FOLDER', size: null, modified: '2026-10-28T15:00:00+08:00' },
    sampleFile('欢迎使用.txt', '这里是网盘界面的演示文件。\n当前更改仅在本页面内有效，不会上传至服务器。\n'),
    sampleFile('项目说明.txt', '在这里整理你的项目资料。\n', '1'),
    sampleFile('阅读清单.txt', '记录想要学习的内容。\n', '2'),
  ]
}

/** All operations are a local, in-memory prototype. Nothing is sent to HDFS. */
export function useDemoDrive() {
  const nodes = ref<DiskNode[]>(initialNodes())
  const currentId = shallowRef('0')
  const search = shallowRef('')

  const visibleNodes = computed(() => {
    const term = search.value.normalize('NFC').toLocaleLowerCase()
    return nodes.value.filter((n) => n.parentId === currentId.value && n.name.toLocaleLowerCase().includes(term))
      .slice().sort((a, b) => (a.type === b.type ? a.name.localeCompare(b.name, 'zh-CN') : a.type === 'FOLDER' ? -1 : 1))
  })

  const breadcrumbs = computed<Breadcrumb[]>(() => {
    const trail: Breadcrumb[] = []
    let cursor = currentId.value
    const visited = new Set<string>()
    while (cursor !== '0' && !visited.has(cursor)) {
      visited.add(cursor)
      const node = nodes.value.find((n) => n.id === cursor && n.type === 'FOLDER')
      if (!node) break
      trail.unshift({ id: node.id, name: node.name })
      cursor = node.parentId
    }
    return [{ id: '0', name: '我的文件' }, ...trail]
  })

  function exists(parentId: string, name: string, exceptId?: string): boolean {
    return nodes.value.some((node) => node.parentId === parentId && node.name === name && node.id !== exceptId)
  }

  function isFolder(id: string): boolean {
    return id === '0' || nodes.value.some((node) => node.id === id && node.type === 'FOLDER')
  }

  function enter(id: string): void {
    if (isFolder(id)) {
      currentId.value = id
      search.value = ''
    }
  }

  function createFolder(value: string): string | null {
    const { name, error } = validateName(value)
    if (error) return error
    if (!isFolder(currentId.value)) return '当前文件夹不存在'
    if (exists(currentId.value, name)) return '当前文件夹中已有同名文件或文件夹'
    nodes.value.push({ id: String(nextId++), parentId: currentId.value, name, type: 'FOLDER', size: null, modified: new Date().toISOString() })
    return null
  }

  function rename(id: string, value: string): string | null {
    const node = nodes.value.find((n) => n.id === id)
    if (!node) return '文件不存在，请刷新后重试'
    const { name, error } = validateName(value)
    if (error) return error
    if (exists(node.parentId, name, id)) return '当前文件夹中已有同名文件或文件夹'
    node.name = name
    node.modified = new Date().toISOString()
    return null
  }

  function move(id: string, targetParentId: string): string | null {
    const node = nodes.value.find((n) => n.id === id)
    if (!node) return '文件不存在，请刷新后重试'
    if (!isFolder(targetParentId)) return '目标文件夹不存在'
    if (node.type === 'FOLDER') {
      let cursor = targetParentId
      const seen = new Set<string>()
      while (cursor !== '0' && !seen.has(cursor)) {
        if (cursor === id) return '不能将文件夹移动到自身或其子文件夹'
        seen.add(cursor)
        cursor = nodes.value.find((n) => n.id === cursor)?.parentId ?? '0'
      }
    }
    if (exists(targetParentId, node.name, id)) return '目标文件夹中已有同名文件或文件夹'
    node.parentId = targetParentId
    node.modified = new Date().toISOString()
    return null
  }

  function remove(id: string): string | null {
    const node = nodes.value.find((n) => n.id === id)
    if (!node) return '文件不存在，请刷新后重试'
    if (node.type === 'FOLDER' && nodes.value.some((n) => n.parentId === id)) return '文件夹不为空，请先清空后再删除'
    nodes.value = nodes.value.filter((n) => n.id !== id)
    return null
  }

  function uniqueName(parentId: string, original: string): string | null {
    const dot = original.lastIndexOf('.')
    const hasExt = dot > 0 && dot < original.length - 1
    const stem = hasExt ? original.slice(0, dot) : original
    const ext = hasExt ? original.slice(dot) : ''
    for (let n = 1; n <= 10000; n++) {
      const suffix = ` (${n})`
      const extension = [...ext].length + suffix.length < MAX_NAME ? ext : ''
      const remaining = MAX_NAME - [...extension].length - suffix.length
      const candidate = [...stem].slice(0, remaining).join('') + suffix + extension
      if (!exists(parentId, candidate)) return candidate
    }
    return null
  }

  function saveFile(file: File, choice?: 'keep' | 'skip'): SaveFileResult {
    const { name: original, error } = validateName(file.name)
    if (error) return { status: 'error', name: file.name, message: error }
    if (!isFolder(currentId.value)) return { status: 'error', name: original, message: '目标文件夹不存在' }
    let name = original
    if (exists(currentId.value, original)) {
      if (!choice) return { status: 'conflict', name: original }
      if (choice === 'skip') return { status: 'skipped', name: original }
      const candidate = uniqueName(currentId.value, original)
      if (!candidate) return { status: 'error', name: original, message: '无法生成不重复的文件名' }
      name = candidate
    }
    nodes.value.push({ id: String(nextId++), parentId: currentId.value, name, type: 'FILE', size: file.size, modified: new Date().toISOString(), data: file })
    return { status: 'added', name }
  }

  return { nodes, currentId, search, visibleNodes, breadcrumbs, enter, createFolder, rename, move, remove, saveFile }
}
