import { onBeforeUnmount, ref, shallowRef } from 'vue'

const CHUNK_SIZE = 8 * 1024 * 1024
const MAX_SIZE = 2 * 1024 * 1024 * 1024
interface ChunkInfo { index: number; sha256: string }
interface UploadState { id: string; parentId: string; name: string; sizeBytes: number; chunkSize: number; uploaded: ChunkInfo[]; chunkHashes: string[]; status: string; nodeId?: string }
interface SavedTask { requestId: string; id?: string; parentId: string; name: string; sizeBytes: number; lastModified: number; chunkHashes?: string[] }
type Resolution = 'FAIL' | 'KEEP_BOTH' | 'SKIP'
type Phase = 'idle' | 'checking' | 'uploading' | 'completing' | 'conflict' | 'failed' | 'done'
class ApiError extends Error { constructor(public status: number, public code: string, message: string) { super(message) } }

async function api<T>(url: string, options: RequestInit = {}): Promise<T> {
  const res = await fetch(`/api/v1${url}`, { credentials: 'same-origin', cache: 'no-store', ...options })
  let body: any = null
  try { body = await res.json() } catch { /* proxy errors may not be JSON */ }
  if (!res.ok) throw new ApiError(res.status, body?.error?.code || '', body?.error?.message || `请求失败（${res.status}）`)
  return body?.data as T
}
async function hash(blob: Blob): Promise<string> {
  const bytes = await blob.arrayBuffer()
  const digest = await crypto.subtle.digest('SHA-256', bytes)
  return Array.from(new Uint8Array(digest), x => x.toString(16).padStart(2, '0')).join('')
}

export function useUpload(userId: string, onExpired: () => void, onComplete: (parentId: string) => void) {
  const key = `netdisk.upload.${userId}`
  const saved = ref<SavedTask | null>(null)
  try {
    const value = JSON.parse(localStorage.getItem(key) || 'null') as SavedTask | null
    if (value && typeof value.requestId === 'string' && typeof value.parentId === 'string' && typeof value.name === 'string' && Number.isSafeInteger(value.sizeBytes)) saved.value = value
  } catch { /* storage is optional */ }
  const phase = shallowRef<Phase>(saved.value ? 'failed' : 'idle')
  const message = shallowRef(saved.value ? '选择原文件以继续上传。' : '')
  const progress = shallowRef(0)
  let controller: AbortController | undefined
  let selected: File | undefined
  const hasSelected = shallowRef(false)
  let csrfToken = ''
  let alive = true
  let generation = 0
  const persist = () => { try { if (saved.value) localStorage.setItem(key, JSON.stringify(saved.value)); else localStorage.removeItem(key) } catch { /* retry remains available until refresh */ } }
  const signal = () => controller!.signal
  async function csrf() { return (await api<{ csrfToken: string }>('/auth/csrf', { signal: signal() })).csrfToken }
  const headers = () => ({ 'X-CSRF-TOKEN': csrfToken })
  function fail(e: unknown) {
    if (!alive || controller?.signal.aborted || (e instanceof DOMException && e.name === 'AbortError')) return
    if (e instanceof ApiError && e.status === 401) { onExpired(); return }
    phase.value = 'failed'; message.value = e instanceof ApiError && e.code === 'CONCURRENT_CHANGE' ? '目录正在被其他操作修改，上传任务已保留，请稍后重试。' : e instanceof Error ? e.message : '上传失败，请重试。'
  }
  async function retryChunk(task: UploadState, index: number, file: File) {
    const requestSignal = signal()
    const start = index * task.chunkSize, part = file.slice(start, Math.min(start + task.chunkSize, file.size))
    const checksum = await hash(part)
    if (requestSignal.aborted) throw new DOMException('Aborted', 'AbortError')
    for (let attempt = 0; ; attempt++) {
      try {
        await api(`/uploads/${encodeURIComponent(task.id)}/chunks/${index}`, { method: 'PUT', headers: { ...headers(), 'Content-Type': 'application/octet-stream', 'X-Chunk-SHA256': checksum }, body: part, signal: requestSignal })
        return part.size
      } catch (e) {
        if (requestSignal.aborted || attempt >= 2 || (e instanceof ApiError && e.status < 500 && e.status !== 429 && e.code !== 'CHUNK_BUSY')) throw e
        await new Promise<void>((resolve, reject) => {
          const timer = setTimeout(resolve, 500 * 2 ** attempt)
          requestSignal.addEventListener('abort', () => { clearTimeout(timer); reject(new DOMException('Aborted', 'AbortError')) }, { once: true })
        })
      }
    }
  }
  async function complete(resolution: Resolution) {
    phase.value = 'completing'; message.value = '正在校验并保存文件…'
    try {
      let result: UploadState | undefined
      for (let attempt = 0; !result; attempt++) {
        try {
          result = await api<UploadState>(`/uploads/${encodeURIComponent(saved.value!.id!)}/complete`, { method: 'POST', headers: { ...headers(), 'Content-Type': 'application/json' }, body: JSON.stringify({ resolution }), signal: signal() })
        } catch (e) {
          if (attempt >= 2 || !(e instanceof ApiError) || !(e.status === 429 || e.status === 503 || e.code === 'FINALIZING')) throw e
          await new Promise(resolve => setTimeout(resolve, 500 * 2 ** attempt))
          if (signal().aborted) throw new DOMException('Aborted', 'AbortError')
        }
      }
      if (result.status !== 'COMPLETED' && result.status !== 'SKIPPED') throw new Error('服务器仍在处理，请稍后重试。')
      const parentId = saved.value!.parentId
      saved.value = null; persist(); selected = undefined; hasSelected.value = false; progress.value = 100; phase.value = 'done'
      message.value = result.status === 'SKIPPED' ? '已跳过同名文件。' : '文件上传完成。'
      onComplete(parentId)
    } catch (e) {
      if (e instanceof ApiError && e.status === 409 && e.code === 'NAME_CONFLICT') { phase.value = 'conflict'; message.value = '当前文件夹已有同名文件或文件夹，请选择处理方式。'; return }
      fail(e)
    }
  }
  async function run(file: File) {
    if (!saved.value) return
    selected = file; hasSelected.value = true; controller?.abort(); controller = new AbortController(); const seq = ++generation; const runSignal = controller.signal; phase.value = 'checking'; message.value = '正在检查上传任务…'; progress.value = 0
    try {
      if (!crypto.subtle) throw new Error('当前浏览器无法计算文件校验值，请使用 HTTPS 和较新浏览器。')
      const hashes: string[] = []
      const count = Math.ceil(file.size / CHUNK_SIZE)
      message.value = '正在计算文件校验值，请稍候…'
      for (let index = 0; index < count; index++) {
        if (runSignal.aborted || seq !== generation) return
        hashes.push(await hash(file.slice(index * CHUNK_SIZE, Math.min((index + 1) * CHUNK_SIZE, file.size))))
      }
      if (runSignal.aborted || seq !== generation) return
      if (saved.value.chunkHashes && (saved.value.chunkHashes.length !== hashes.length || hashes.some((h, i) => h !== saved.value!.chunkHashes![i]))) throw new Error('所选文件的内容与原上传任务不一致，请重新选择原文件。')
      saved.value.chunkHashes = hashes; persist()
      csrfToken = await csrf()
      let task: UploadState
      if (saved.value.id) task = await api<UploadState>(`/uploads/${encodeURIComponent(saved.value.id)}`, { signal: signal() })
      else {
        task = await api<UploadState>('/uploads', { method: 'POST', headers: { ...headers(), 'Content-Type': 'application/json' }, body: JSON.stringify({ parentId: saved.value.parentId, name: saved.value.name, sizeBytes: saved.value.sizeBytes, requestId: saved.value.requestId, chunkHashes: hashes }), signal: signal() })
        saved.value.id = task.id; persist()
      }
      if (runSignal.aborted || seq !== generation) return
      if (task.chunkSize !== CHUNK_SIZE || !Array.isArray(task.chunkHashes) || task.chunkHashes.length !== hashes.length || hashes.some((h, i) => h !== task.chunkHashes[i]?.toLowerCase())) throw new Error('所选文件的内容与原上传任务不一致，请重新选择原文件。')
      if (task.parentId !== saved.value.parentId || task.name !== saved.value.name || task.sizeBytes !== saved.value.sizeBytes) throw new Error('服务器上传任务与所选文件不一致。')
      if (!Number.isSafeInteger(task.chunkSize) || task.chunkSize <= 0 || !Array.isArray(task.uploaded)) throw new Error('服务器返回的上传任务无效。')
      if (task.status === 'COMPLETED' || task.status === 'SKIPPED') { const parentId = saved.value.parentId; saved.value = null; persist(); phase.value = 'done'; message.value = '上传任务已结束。'; onComplete(parentId); return }
      if (task.status === 'CONFLICT') { phase.value = 'conflict'; message.value = '当前文件夹已有同名文件或文件夹，请选择处理方式。'; return }
      const known = new Set<number>()
      let bytesDone = 0
      for (const entry of task.uploaded) {
        if (runSignal.aborted || seq !== generation) return
        if (!Number.isInteger(entry.index) || entry.index < 0 || entry.index >= count || known.has(entry.index) || !/^[a-f0-9]{64}$/i.test(entry.sha256)) throw new Error('服务器返回的分片信息无效。')
        const start = entry.index * task.chunkSize, part = file.slice(start, Math.min(start + task.chunkSize, file.size))
        if ((await hash(part)).toLowerCase() !== entry.sha256.toLowerCase()) throw new Error('所选文件与已上传分片不一致，请选择原文件。')
        known.add(entry.index); bytesDone += part.size
        progress.value = file.size ? Math.floor(bytesDone / file.size * 100) : 0
      }
      phase.value = 'uploading'; message.value = '正在上传文件分片…'
      const pending = Array.from({ length: count }, (_, index) => index).filter(index => !known.has(index))
      let next = 0
      const worker = async () => {
        while (next < pending.length && !runSignal.aborted && seq === generation) {
          const i = pending[next++]!
          bytesDone += await retryChunk(task, i, file)
          progress.value = file.size ? Math.floor(bytesDone / file.size * 100) : 100
        }
      }
      const results = await Promise.allSettled(Array.from({ length: Math.min(3, pending.length) }, worker))
      const rejected = results.find((r): r is PromiseRejectedResult => r.status === 'rejected')
      if (rejected) throw rejected.reason
      if (runSignal.aborted || seq !== generation) return
      await complete('FAIL')
    } catch (e) { fail(e) }
  }
  function start(file: File, parentId: string) {
    if (saved.value) { message.value = '请先继续或放弃当前上传任务。'; return }
    if (file.size > MAX_SIZE) { phase.value = 'failed'; message.value = '文件不能超过 2 GiB。'; return }
    saved.value = { requestId: crypto.randomUUID(), parentId, name: file.name, sizeBytes: file.size, lastModified: file.lastModified }; persist(); void run(file)
  }
  function resume(file: File) {
    if (!saved.value) return
    if (file.name !== saved.value.name || file.size !== saved.value.sizeBytes) { phase.value = 'failed'; message.value = '请选择原文件（名称与大小必须一致）。'; return }
    void run(file)
  }
  function retry() { if (selected) void run(selected) }
  function resolve(resolution: 'KEEP_BOTH' | 'SKIP') { if (phase.value === 'conflict') void complete(resolution) }
  async function cancel() {
    if (!saved.value) return
    generation++; controller?.abort(); controller = new AbortController()
    phase.value = 'checking'; message.value = '正在取消上传…'
    try {
      // Before the first POST, the complete manifest is saved; a lost create response can be recovered idempotently.
      if (!saved.value.id && !saved.value.chunkHashes) {
        saved.value = null; persist(); selected = undefined; hasSelected.value = false; phase.value = 'idle'; message.value = ''; progress.value = 0; return
      }
      csrfToken = await csrf()
      if (!saved.value.id) {
        const task = await api<UploadState>('/uploads', { method: 'POST', headers: { ...headers(), 'Content-Type': 'application/json' }, body: JSON.stringify({ parentId: saved.value.parentId, name: saved.value.name, sizeBytes: saved.value.sizeBytes, requestId: saved.value.requestId, chunkHashes: saved.value.chunkHashes }), signal: signal() })
        saved.value.id = task.id; persist()
      }
      const result = await api<UploadState>(`/uploads/${encodeURIComponent(saved.value.id!)}/cancel`, { method: 'POST', headers: headers(), signal: signal() })
      if (result.status !== 'SKIPPED') throw new Error('服务器未确认取消，请稍后重试。')
      saved.value = null; persist(); selected = undefined; hasSelected.value = false; phase.value = 'done'; message.value = '上传已取消。'; progress.value = 0
    } catch (e) { fail(e) }
  }
  onBeforeUnmount(() => { alive = false; controller?.abort() })
  return { saved, phase, message, progress, hasSelected, start, resume, retry, resolve, cancel }
}
