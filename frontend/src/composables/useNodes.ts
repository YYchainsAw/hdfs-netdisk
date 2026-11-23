import { computed, onBeforeUnmount, onMounted, ref, shallowRef } from 'vue'

export interface NodeItem { id: string; parentId: string; name: string; type: 'FILE' | 'FOLDER'; sizeBytes: string | null; version: string; updatedAt: string }
interface Listing { items: NodeItem[]; page: number; pageSize: number; hasMore: boolean }
interface Envelope<T> { data: T }
class ApiError extends Error { constructor(public status: number, message: string) { super(message) } }

async function request<T>(path: string, options: RequestInit = {}): Promise<T> {
  let res: Response
  try { res = await fetch(`/api/v1${path}`, { credentials: 'same-origin', cache: 'no-store', ...options }) }
  catch (e) { if (options.signal?.aborted) throw e; throw new Error('无法连接服务器，请检查网络后重试。') }
  if (!res.ok) {
    let message = res.status === 401 ? '登录已过期，请重新登录。' : '操作未完成，请稍后重试。'
    try { const body = await res.json(); if (typeof body.error?.message === 'string') message = body.error.message } catch { /* response may not be JSON */ }
    throw new ApiError(res.status, message)
  }
  if (res.status === 204) return undefined as T
  return (await res.json() as Envelope<T>).data
}

export function useNodes(onUnauthenticated: () => void) {
  const path = ref<{ id: string; name: string }[]>([{ id: '0', name: '我的文件' }])
  const items = ref<NodeItem[]>([])
  const keyword = shallowRef('')
  const loading = shallowRef(false)
  const saving = shallowRef(false)
  const error = shallowRef('')
  const dialogError = shallowRef('')
  const page = shallowRef(1)
  const hasMore = shallowRef(false)
  const currentId = computed(() => path.value[path.value.length - 1]!.id)
  let pending: AbortController | undefined
  let generation = 0
  let mounted = true

  function handleError(e: unknown): string {
    if (e instanceof ApiError && e.status === 401) { onUnauthenticated(); return '登录已过期，请重新登录。' }
    return e instanceof Error ? e.message : '连接服务失败，请稍后重试。'
  }
  async function load(nextPage = 1) {
    pending?.abort()
    const controller = new AbortController(); pending = controller
    const seq = ++generation, parentId = currentId.value
    loading.value = true; error.value = ''
    try {
      const params = new URLSearchParams({ parentId, keyword: keyword.value, page: String(nextPage), pageSize: '50' })
      const result = await request<Listing>(`/nodes?${params}`, { signal: controller.signal })
      if (!mounted || seq !== generation) return
      items.value = result.items; page.value = result.page; hasMore.value = result.hasMore
    } catch (e) {
      if (controller.signal.aborted || !mounted || seq !== generation) return
      error.value = handleError(e)
    } finally { if (mounted && seq === generation) loading.value = false }
  }
  function go(index: number) { if (saving.value) return; path.value = path.value.slice(0, index + 1); keyword.value = ''; void load() }
  function open(node: NodeItem) { if (saving.value || node.type !== 'FOLDER') return; path.value.push({ id: node.id, name: node.name }); keyword.value = ''; void load() }
  async function create(name: string): Promise<boolean> {
    if (saving.value) return false
    saving.value = true; dialogError.value = ''
    const parentId = currentId.value
    try {
      const { csrfToken } = await request<{ csrfToken: string }>('/auth/csrf')
      await request<NodeItem>('/folders', { method: 'POST', headers: { 'Content-Type': 'application/json', 'X-CSRF-TOKEN': csrfToken }, body: JSON.stringify({ parentId, name }) })
      await load(); return true
    } catch (e) { dialogError.value = handleError(e); return false }
    finally { saving.value = false }
  }
  async function mutate(node: NodeItem, operation: 'rename' | 'delete', name?: string): Promise<boolean> {
    if (saving.value) return false
    saving.value = true; dialogError.value = ''
    try {
      const { csrfToken } = await request<{ csrfToken: string }>('/auth/csrf')
      const path = `/nodes/${encodeURIComponent(node.id)}${operation === 'rename' ? '/name' : ''}`
      await request(path, {
        method: operation === 'rename' ? 'PATCH' : 'DELETE',
        headers: { 'X-CSRF-TOKEN': csrfToken, 'If-Match': `"v${node.version}"`, ...(operation === 'rename' ? { 'Content-Type': 'application/json' } : {}) },
        ...(operation === 'rename' ? { body: JSON.stringify({ name }) } : {}),
      })
      await load(page.value); return true
    } catch (e) {
      dialogError.value = e instanceof ApiError && e.status === 412
        ? '文件已被其他操作修改，请关闭窗口并刷新列表后重试。'
        : handleError(e)
      return false
    } finally { saving.value = false }
  }
  onMounted(() => { void load() })
  onBeforeUnmount(() => { mounted = false; pending?.abort() })
  return { path, items, keyword, loading, saving, error, dialogError, page, hasMore, load, go, open, create, mutate }
}
