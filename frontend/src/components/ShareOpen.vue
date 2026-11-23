<script setup lang="ts">
import { onMounted, ref } from 'vue'
const props = defineProps<{ token: string }>()
const name=ref(''), size=ref(''), code=ref(''), verified=ref(false), loading=ref(true), busy=ref(false), error=ref('')
async function api(path: string, options: RequestInit = {}) {
 const res=await fetch(`/api/v1${path}`,{ credentials:'same-origin',cache:'no-store',...options })
 if(!res.ok) { let msg='分享链接不存在或已失效'; try { msg=(await res.json()).error?.message || msg } catch { /* no JSON */ } throw new Error(msg) }
 return (await res.json()).data
}
const path=`/public/shares/${encodeURIComponent(props.token)}`
onMounted(async()=>{try {const data=await api(path);name.value=data.name;size.value=data.sizeBytes} catch(e) {error.value=(e as Error).message} finally {loading.value=false}})
async function submit() {busy.value=true;error.value='';try {const csrf=await api('/auth/csrf');await api(`${path}/verify`,{method:'POST',headers:{'Content-Type':'application/json','X-CSRF-TOKEN':csrf.csrfToken},body:JSON.stringify({code:code.value})});code.value='';verified.value=true} catch(e){error.value=(e as Error).message}finally{busy.value=false}}
</script>
<template>
 <main class="page"><section class="card"><span class="brand">☁ 云屿</span><h1>文件分享</h1><p v-if="loading">正在读取分享…</p><template v-else-if="name"><div class="file"><b>{{ name }}</b><span>{{ size }} B</span></div><form v-if="!verified" @submit.prevent="submit"><label for="share-code">请输入提取码</label><input id="share-code" v-model="code" autocomplete="off" inputmode="numeric" maxlength="6" required placeholder="6 位提取码"><button :disabled="busy">{{ busy ? '验证中…' : '验证提取码' }}</button></form><a v-else class="download" :href="`/api/v1${path}/content`">下载文件</a></template><p v-if="error" class="error" role="alert">{{ error }}</p><a class="home" href="/">返回网盘</a></section></main>
</template>
<style scoped>
.page{min-height:100dvh;display:grid;place-items:center;background:#f3f7fc;padding:20px;color:#2e4561}.card{width:min(440px,100%);padding:35px;background:white;border:1px solid #e6edf5;border-radius:16px;box-shadow:0 16px 40px #233f5b12}.brand{color:#6689b5;font-weight:700}h1{font-size:22px;margin:25px 0}.file{display:grid;gap:8px;padding:17px;background:#f5f8fc;border-radius:9px;word-break:break-all}.file span,p{font-size:13px;color:#8393a6}form{display:grid;gap:10px;margin-top:22px}label{font-size:13px}input{padding:11px;border:1px solid #dce4ee;border-radius:8px;font:inherit}button,.download{display:block;text-align:center;padding:11px;background:#3c67a9;color:white;border:0;border-radius:8px;text-decoration:none;font:inherit}.download{margin-top:20px}.error{color:#b84a4a}.home{display:block;margin-top:25px;color:#6883a2;font-size:12px}
</style>
