import { onBeforeUnmount, onMounted, ref, shallowRef } from 'vue'

export interface TrashItem { id: string; parentId: string; name: string; type: 'FILE' | 'FOLDER'; sizeBytes: string | null; version: string; deletedAt: string }
interface TrashListing { items: TrashItem[]; page: number; pageSize: number; hasMore: boolean }
class TrashError extends Error { constructor(public status: number, public code: string, message: string) { super(message) } }

async function request<T>(path: string, options: RequestInit = {}): Promise<T> {
  let res: Response
  try { res = await fetch(`/api/v1${path}`, { credentials: 'same-origin', cache: 'no-store', ...options }) }
  catch (e) { if (options.signal?.aborted) throw e; throw new Error('无法连接服务器，请检查网络后重试。') }
  if (!res.ok) {
    let code = '', message = res.status === 401 ? '登录已过期，请重新登录。' : '操作未完成，请稍后重试。'
    try { const body = await res.json(); if (typeof body.error?.message === 'string') message = body.error.message; if (typeof body.error?.code === 'string') code = body.error.code } catch { /* errors may be returned by proxy */ }
    throw new TrashError(res.status, code, message)
  }
  if (res.status === 204) return undefined as T
  return (await res.json() as { data: T }).data
}

export function useTrash(onUnauthenticated: () => void, onChanged: () => void) {
  const items = ref<TrashItem[]>([])
  const loading = shallowRef(false), saving = shallowRef(false), error = shallowRef(''), actionError = shallowRef('')
  const page = shallowRef(1), hasMore = shallowRef(false)
  let pending: AbortController | undefined
  let sequence = 0, mounted = true
  function message(e: unknown): string {
    if (e instanceof TrashError) {
      if (e.status === 401) { onUnauthenticated(); return '登录已过期，请重新登录。' }
      if (e.code === 'CONCURRENT_CHANGE') return '目录正在被其他操作修改，请刷新回收站后重试。'
      if (e.code === 'NAME_CONFLICT') return '原目录中已有同名文件或文件夹，请先更改现有项目的名称。'
      if (e.code === 'TREE_TOO_LARGE') return '单次最多处理 1000 个项目，请先分批处理。'
      if (e.code === 'NODE_BUSY') return '文件夹内存在已单独删除或正在清理的项目，请先处理或等待清理完成。'
      if (e.code === 'UPLOAD_ACTIVE') return '文件夹内有正在进行的上传任务，请先完成或取消上传。'
      if (e.code === 'PARENT_UNAVAILABLE') return '原目录已不可用，请先恢复原目录后再试。'
      if (e.status === 412) return '该项目已发生变化，请关闭窗口并刷新回收站。'
    }
    return e instanceof Error ? e.message : '操作未完成，请稍后重试。'
  }
  async function load(nextPage = 1) {
    pending?.abort()
    const controller = new AbortController(); pending = controller
    const current = ++sequence
    loading.value = true; error.value = ''
    try {
      const result = await request<TrashListing>(`/trash?page=${nextPage}&pageSize=50`, { signal: controller.signal })
      if (!mounted || current !== sequence) return
      items.value = result.items; page.value = result.page; hasMore.value = result.hasMore
    } catch (e) { if (mounted && current === sequence && !controller.signal.aborted) error.value = message(e) }
    finally { if (mounted && current === sequence) loading.value = false }
  }
  async function apply(item: TrashItem, operation: 'restore' | 'purge'): Promise<boolean> {
    if (saving.value) return false
    saving.value = true; actionError.value = ''
    try {
      const { csrfToken } = await request<{ csrfToken: string }>('/auth/csrf')
      await request(`/trash/${encodeURIComponent(item.id)}${operation === 'restore' ? '/restore' : ''}`, {
        method: operation === 'restore' ? 'POST' : 'DELETE', headers: { 'X-CSRF-TOKEN': csrfToken, 'If-Match': `"v${item.version}"` },
      })
      if (!mounted) return false
      await load(items.value.length === 1 && page.value > 1 ? page.value - 1 : page.value)
      if (mounted) onChanged()
      return true
    } catch (e) { if (mounted) actionError.value = message(e); return false }
    finally { if (mounted) saving.value = false }
  }
  onMounted(() => { void load() })
  onBeforeUnmount(() => { mounted = false; pending?.abort() })
  return { items, loading, saving, error, actionError, page, hasMore, load, apply }
}



