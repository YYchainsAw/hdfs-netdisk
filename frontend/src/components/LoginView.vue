<script setup lang="ts">
import { shallowRef } from 'vue'

defineProps<{ busy: boolean; error: string }>()
const emit = defineEmits<{ enter: []; login: [credentials: { username: string; password: string }] }>()
const username = shallowRef('')
const password = shallowRef('')
function submit() {
  const credentials = { username: username.value, password: password.value }
  password.value = ''
  emit('login', credentials)
}
</script>

<template>
  <main class="entry">
    <div class="entry-shell">
      <header class="brand" aria-label="云屿网盘">
        <span class="brand-mark" aria-hidden="true">
          <svg viewBox="0 0 32 32" fill="none"><path d="M5 20.5A5.5 5.5 0 0 1 10.5 15h1.2a6.7 6.7 0 0 1 12.8 1.8A4.2 4.2 0 0 1 24 25H10a5 5 0 0 1-5-4.5Z" stroke="currentColor" stroke-width="2" stroke-linejoin="round"/><path d="M12 19h8M14 22h4" stroke="currentColor" stroke-width="2" stroke-linecap="round"/></svg>
        </span>
        <span class="brand-name">云屿<span class="brand-divider"></span><span class="brand-sub">个人网盘</span></span>
      </header>

      <div class="entry-layout">
        <section class="intro" aria-labelledby="welcome-title">
          <span class="eyebrow"><span></span> 一个安静的文件空间</span>
          <h1 id="welcome-title">把重要的，<br><span>好好收起来。</span></h1>
          <p class="intro-copy">工作文档、学习资料和生活记录，<br class="desktop-only">都可以在这里整理得井井有条。</p>

          <div class="illustration" aria-hidden="true">
            <div class="illustration-top"><span class="tiny-mark">●</span><span>你的文件空间</span><span class="illustration-dots">•••</span></div>
            <div class="illustration-content">
              <div class="folder folder-back"><span></span></div>
              <div class="sheet sheet-one"><div></div><div></div><div></div></div>
              <div class="sheet sheet-two"><div></div><div></div><div></div></div>
              <div class="folder folder-front"><span></span><b>MY FILES</b></div>
              <div class="floating-tag"><span class="tag-icon">✓</span><span>整理好每一份重要</span></div>
            </div>
            <div class="illustration-bottom"><span>一个有序的开始</span><span>YOUR SPACE&nbsp; ↗</span></div>
          </div>
        </section>

        <section class="welcome-card" aria-labelledby="card-title">
          <div class="card-overline">WELCOME TO YOUR SPACE</div>
          <h2 id="card-title">登录云屿</h2>
          <p class="card-copy">使用你的网盘账号，回到自己的文件空间。</p>
          <form class="login-form" @submit.prevent="submit">
            <label class="field-label" for="username">账号</label>
            <input id="username" v-model="username" class="login-input" name="username" autocomplete="username" placeholder="请输入账号" required :disabled="busy" maxlength="64" />
            <label class="field-label" for="password">密码</label>
            <input id="password" v-model="password" class="login-input" name="password" type="password" autocomplete="current-password" placeholder="请输入密码" required :disabled="busy" />
            <p v-if="error" class="login-error" role="alert">{{ error }}</p>
            <button class="enter-button" type="submit" :disabled="busy"><span>{{ busy ? '登录中…' : '登录' }}</span><span aria-hidden="true">↗</span></button>
          </form>
          <div class="demo-notice"><span class="notice-icon" aria-hidden="true">i</span><div><strong>想先看看？</strong><p>体验空间使用示例数据，不会修改服务器上的文件。</p></div></div>
          <button class="demo-button" type="button" :disabled="busy" @click="emit('enter')">进入演示空间 <span aria-hidden="true">→</span></button>
        </section>
      </div>

      <footer class="entry-footer"><span>云屿 · 你的个人文件空间</span><span>让每一份文件，都有自己的位置</span></footer>
    </div>
  </main>
</template>

<style scoped>
.entry{min-height:100dvh;background:#f4f7fb;color:#1c314a;font-family:Inter,"Noto Sans SC","Microsoft YaHei",sans-serif;display:flex;justify-content:center}
.entry *{box-sizing:border-box}.entry-shell{width:min(1440px,100%);min-height:100dvh;padding:30px 6.3% 24px;display:flex;flex-direction:column}.brand{display:flex;align-items:center;gap:10px;color:#1e3551}.brand-mark{width:38px;height:38px;display:grid;place-items:center;border-radius:12px;background:#375e9b;color:white}.brand-mark svg{width:29px;height:29px}.brand-name{font-size:18px;font-weight:750;letter-spacing:.08em;display:flex;align-items:center}.brand-divider{width:1px;height:16px;background:#c6d0dd;margin:0 11px}.brand-sub{font-size:12px;font-weight:500;color:#71839a;letter-spacing:.03em}
.entry-layout{display:grid;grid-template-columns:minmax(0,1.15fr) minmax(340px,.85fr);gap:9%;align-items:center;flex:1;padding:48px 0 55px}.intro{max-width:610px}.eyebrow{display:inline-flex;align-items:center;gap:8px;color:#65809c;font-size:12px;letter-spacing:.18em;font-weight:600}.eyebrow span{width:6px;height:6px;border-radius:50%;background:#74aa99}.intro h1{margin:24px 0 22px;color:#1c3452;font-size:clamp(38px,4.4vw,64px);letter-spacing:.02em;line-height:1.24;font-weight:750}.intro h1 span{color:#6482a7}.intro-copy{margin:0;color:#6e8095;font-size:15px;line-height:1.95}
.illustration{margin-top:39px;max-width:510px;height:245px;position:relative;border-radius:18px;border:1px solid #d9e4f0;background:linear-gradient(135deg,#eef4fb,#e5eef8);overflow:hidden;box-shadow:0 14px 45px rgba(51,81,116,.07)}.illustration-top,.illustration-bottom{position:absolute;left:20px;right:20px;display:flex;align-items:center;gap:9px;color:#8094ad;font-size:10px}.illustration-top{top:15px}.tiny-mark{color:#aac2de;font-size:11px}.illustration-dots{margin-left:auto;letter-spacing:3px}.illustration-bottom{bottom:14px;justify-content:space-between;font-size:10px;letter-spacing:.04em}.illustration-content{position:absolute;inset:43px 30px 40px}.folder{position:absolute;left:28%;bottom:2px;width:150px;height:105px;border-radius:8px 10px 13px 13px}.folder-back{background:#a8bede;transform:rotate(-8deg);box-shadow:0 12px 22px rgba(66,97,137,.13)}.folder-back span,.folder-front span{position:absolute;left:0;top:-11px;width:64px;height:16px;background:inherit;border-radius:6px 7px 0 0}.folder-front{left:35%;bottom:0;background:linear-gradient(145deg,#7398c5,#5a80b2);transform:rotate(3deg);box-shadow:0 12px 22px rgba(66,97,137,.2)}.folder-front b{position:absolute;bottom:15px;left:17px;color:#e2ecf8;font-size:9px;letter-spacing:.14em;font-weight:600}.sheet{position:absolute;left:37%;bottom:34px;width:95px;height:106px;background:#fff;border:1px solid #d9e4f1;border-radius:6px;box-shadow:0 8px 20px rgba(80,106,143,.1);padding:22px 13px}.sheet-one{transform:rotate(-11deg)}.sheet-two{left:42%;transform:rotate(10deg)}.sheet div{height:4px;border-radius:5px;background:#e1eaf4;margin-bottom:10px}.sheet div:nth-child(2){width:80%}.sheet div:nth-child(3){width:55%}.floating-tag{position:absolute;right:1%;bottom:22px;display:flex;align-items:center;gap:7px;background:white;padding:9px 12px;border-radius:9px;color:#61758e;font-size:10px;box-shadow:0 8px 25px rgba(68,95,130,.13)}.tag-icon{width:18px;height:18px;display:grid;place-items:center;border-radius:50%;background:#e5f4ed;color:#5b9b80;font-size:11px}
.welcome-card{width:100%;max-width:430px;justify-self:end;background:white;border:1px solid #e3eaf3;box-shadow:0 24px 70px rgba(40,67,101,.08);border-radius:22px;padding:48px 42px 38px}.card-overline{font-size:10px;letter-spacing:.16em;color:#8a9bb0;font-weight:700}.welcome-card h2{font-size:27px;letter-spacing:.02em;margin:20px 0 10px;color:#213954}.card-copy{color:#8392a4;line-height:1.7;font-size:14px;margin:0}.demo-notice{display:flex;gap:12px;border:1px solid #dfe9f5;background:#f4f8fd;border-radius:12px;margin:35px 0 24px;padding:17px 15px}.notice-icon{display:grid;place-items:center;flex:none;width:19px;height:19px;border:1px solid #92afd0;border-radius:50%;font-family:Georgia,serif;font-size:13px;color:#6589b3}.demo-notice strong{font-size:13px;color:#345b85}.demo-notice p{margin:5px 0 0;color:#748ba3;font-size:12px;line-height:1.65}.enter-button{border:0;width:100%;padding:15px 18px;border-radius:10px;background:#315d9b;color:#fff;display:flex;justify-content:space-between;align-items:center;font:inherit;font-size:14px;font-weight:650;cursor:pointer;transition:background .18s,transform .18s}.enter-button:hover{background:#264e87;transform:translateY(-1px)}.enter-button:focus-visible{outline:3px solid #92b4e8;outline-offset:3px}.privacy-note{margin:19px 0 0;color:#a0adbb;font-size:12px;display:flex;justify-content:center;align-items:center;gap:6px}.privacy-note svg{width:16px;height:16px}.entry-footer{display:flex;justify-content:space-between;gap:20px;color:#a2afbd;font-size:11px;letter-spacing:.02em}
.login-form{display:flex;flex-direction:column;gap:8px;margin-top:25px}.field-label{font-size:12px;color:#56708a;font-weight:650;margin-top:5px}.login-input{width:100%;height:44px;border:1px solid #dbe5ef;border-radius:9px;background:#fbfcfe;color:#243c55;padding:0 13px;font:inherit;font-size:14px}.login-input::placeholder{color:#a6b6c7}.login-form .enter-button{margin-top:11px}.login-error{color:#b34f4c;margin:2px 0;font-size:12px;line-height:1.5}.demo-notice{margin:21px 0 12px;padding:13px 14px}.demo-button{width:100%;border:1px solid #dbe5ef;background:#fff;color:#547392;border-radius:10px;padding:12px 16px;display:flex;justify-content:space-between;font:inherit;font-size:13px;font-weight:600;cursor:pointer}.demo-button:hover{background:#f7faff}.demo-button:focus-visible{outline:3px solid #92b4e8;outline-offset:3px}
@media(max-width:760px){.entry-shell{padding:22px 22px 24px}.entry-layout{grid-template-columns:1fr;gap:32px;padding:48px 0;align-content:center}.intro h1{font-size:38px;margin:18px 0 14px}.intro-copy{font-size:14px}.illustration{height:165px;margin-top:24px}.illustration-content{transform:scale(.7);transform-origin:center;inset:14px 0 3px}.welcome-card{justify-self:stretch;max-width:none;padding:32px 25px 27px}.demo-notice{margin:24px 0 18px}.entry-footer span:last-child{display:none}.desktop-only{display:none}}
@media(prefers-reduced-motion:reduce){.enter-button{transition:none}}
</style>
