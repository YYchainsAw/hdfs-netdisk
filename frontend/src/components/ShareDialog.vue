<script setup lang="ts">
import { onMounted, ref } from 'vue'
const props = defineProps<{ nodeId: string; name: string }>()
const emit = defineEmits<{ close: [] }>()
type Share = { id: string; expiresAt: string; accessCount: string }
const items = ref<Share[]>([]), hours = ref(24), maxVisits = ref(''), busy = ref(false), error = ref('')
const result = ref<{ token: string; code: string; expiresAt: string } | null>(null)
async function api(path: string, options: RequestInit = {}) {
  const res = await fetch(`/api/v1${path}`, { credentials: 'same-origin', cache: 'no-store', ...options })
  if (!res.ok) { let msg = '操作失败，请重试'; try { msg = (await res.json()).error?.message || msg } catch { /* non-JSON response */ } throw new Error(msg) }
  return res.status === 204 ? null : (await res.json()).data
}
async function list() { try { items.value = await api(`/shares?nodeId=${encodeURIComponent(props.nodeId)}`) } catch (e) { error.value = (e as Error).message } }
async function csrf() { return (await api('/auth/csrf')).csrfToken as string }
async function create() {
  busy.value = true; error.value = ''
  try { const t = await csrf(); result.value = await api('/shares', { method: 'POST', headers: { 'Content-Type': 'application/json', 'X-CSRF-TOKEN': t }, body: JSON.stringify({ nodeId: props.nodeId, expiresHours: hours.value, maxVisits: maxVisits.value === "" ? null : Number(maxVisits.value) }) }); await list() }
  catch (e) { error.value = (e as Error).message } finally { busy.value = false }
}
async function revoke(id: string) { busy.value = true; error.value = ''; try { const t = await csrf(); await api(`/shares/${encodeURIComponent(id)}`, { method: 'DELETE', headers: { 'X-CSRF-TOKEN': t } }); await list() } catch (e) { error.value = (e as Error).message } finally { busy.value = false } }
function link(token: string) { return `${location.origin}/s/${encodeURIComponent(token)}` }
onMounted(list)
</script>
<template>
  <div class="overlay" @click.self="emit('close')"><section class="dialog" role="dialog" aria-modal="true" aria-labelledby="share-title" @keydown.esc="emit('close')">
    <header><h2 id="share-title">分享文件</h2><button @click="emit('close')" aria-label="关闭">×</button></header>
    <p class="name">{{ name }}</p><p>链接和提取码只在创建时显示，请及时保存。重命名或删除文件后链接将失效。</p>
    <label>有效期 <select v-model.number="hours"><option :value="1">1 小时</option><option :value="24">1 天</option><option :value="168">7 天</option><option :value="720">30 天</option></select></label>
    <label class="visit-limit">访问次数上限 <input v-model="maxVisits" type="number" min="1" max="10000" placeholder="留空不限"></label><button class="primary" :disabled="busy" @click="create">{{ busy ? '处理中…' : '创建分享' }}</button>
    <div v-if="result" class="result"><label>分享链接<input readonly :value="link(result.token)" @focus="($event.target as HTMLInputElement).select()"></label><label>提取码<input readonly :value="result.code" @focus="($event.target as HTMLInputElement).select()"></label><small>到期：{{ new Date(result.expiresAt).toLocaleString() }}</small></div>
    <p v-if="error" class="error" role="alert">{{ error }}</p>
    <div v-if="items.length"><h3>有效分享</h3><div v-for="s in items" :key="s.id" class="row"><span>到期 {{ s.expiresAt }} · 访问 {{ s.accessCount }} 次</span><button :disabled="busy" @click="revoke(s.id)">取消分享</button></div></div>
  </section></div>
</template>
<style scoped>
.overlay{position:fixed;inset:0;background:#14243880;display:grid;place-items:center;padding:16px;z-index:100}.dialog{background:white;border-radius:14px;padding:24px;width:min(480px,100%);max-height:85vh;overflow:auto;color:#253b52;box-shadow:0 14px 45px #12263c33}header,.row{display:flex;justify-content:space-between;align-items:center;gap:12px}h2{font-size:19px;margin:0}h3{font-size:14px;margin-top:22px}p,small,.row{font-size:13px;color:#6d7e90;line-height:1.6}.name{color:#304d6a;font-weight:600;word-break:break-all}button,select,input{font:inherit}button,select{border:1px solid #d8e2ed;border-radius:7px;padding:8px 12px;background:white;color:#44658a;cursor:pointer}.visit-limit{display:block;margin-top:12px;font-size:13px}.visit-limit input{width:110px;padding:7px;border:1px solid #dce5ef;border-radius:6px}.primary{margin-top:12px;background:#3c67a9;color:white;border:0;margin-left:12px}.result{margin-top:16px;display:grid;gap:10px;padding:12px;background:#f2f6fb;border-radius:8px}.result label{display:grid;gap:4px;font-size:12px}.result input{width:100%;padding:7px;border:1px solid #dce5ef;border-radius:5px}.row{padding:7px 0;border-top:1px solid #edf1f5}.row button{flex:none}.error{color:#b64a4a}
</style>


