import { onMounted, shallowRef } from 'vue'

export interface AuthUser { id: string; username: string }
interface Envelope<T> { data: T }
interface ApiError { error?: { code?: string; message?: string } }

class RequestError extends Error {
  constructor(public readonly status: number, message: string) { super(message) }
}

async function request<T>(path: string, options: RequestInit = {}): Promise<T> {
  let response: Response
  try {
    response = await fetch(`/api/v1/auth${path}`, { ...options, credentials: 'same-origin', cache: 'no-store' })
  } catch {
    throw new RequestError(0, '无法连接登录服务，请确认服务已启动。')
  }
  if (!response.ok) {
    let error: ApiError = {}
    try { error = await response.json() as ApiError } catch { /* An upstream error may not be JSON. */ }
    const message = response.status === 401 ? '账号或密码不正确，或登录已过期。'
      : response.status === 429 ? '尝试次数过多，请稍后再试。'
      : response.status === 403 ? '请求验证失败，请刷新页面后重试。'
      : error.error?.message || '登录服务暂时不可用，请稍后重试。'
    throw new RequestError(response.status, message)
  }
  if (response.status === 204) return undefined as T
  try { return (await response.json() as Envelope<T>).data }
  catch { throw new RequestError(response.status, '服务响应异常，请稍后重试。') }
}

async function csrf(): Promise<string> {
  const data = await request<{ csrfToken: string }>('/csrf')
  if (!data?.csrfToken) throw new RequestError(500, '无法获取请求验证信息，请稍后重试。')
  return data.csrfToken
}

export function useAuth() {
  const user = shallowRef<AuthUser | null>(null)
  const busy = shallowRef(false)
  const checking = shallowRef(true)
  const error = shallowRef('')
  const csrfToken = shallowRef('')

  onMounted(async () => {
    try {
      user.value = await request<AuthUser>('/me')
    } catch (e) {
      if (!(e instanceof RequestError && e.status === 401)) {
        error.value = e instanceof Error ? e.message : '无法连接登录服务。'
      }
    } finally { checking.value = false }
  })

  async function login(credentials: { username: string; password: string }): Promise<void> {
    if (busy.value) return
    busy.value = true; error.value = ''
    try {
      const token = await csrf()
      const result = await request<{ user: AuthUser; csrfToken: string }>('/login', {
        method: 'POST', headers: { 'Content-Type': 'application/json', 'X-CSRF-TOKEN': token },
        body: JSON.stringify(credentials),
      })
      if (!result?.user || !result.csrfToken) throw new RequestError(500, '服务响应异常，请稍后重试。')
      csrfToken.value = result.csrfToken
      user.value = result.user
    } catch (e) { error.value = e instanceof Error ? e.message : '登录失败，请稍后重试。' }
    finally { busy.value = false }
  }

  async function logout(): Promise<void> {
    if (busy.value) return
    busy.value = true; error.value = ''
    try {
      const token = csrfToken.value || await csrf()
      await request<void>('/logout', { method: 'POST', headers: { 'X-CSRF-TOKEN': token } })
      user.value = null; csrfToken.value = ''
    } catch (e) {
      if (e instanceof RequestError && e.status === 401) { user.value = null; csrfToken.value = '' }
      else error.value = e instanceof Error ? e.message : '退出失败，请稍后重试。'
    } finally { busy.value = false }
  }

  return { user, busy, checking, error, login, logout }
}
