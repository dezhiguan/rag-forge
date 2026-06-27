<template>
  <div class="auth-screen">
    <aside class="auth-brand" aria-label="RAGForge">
      <div class="brand-top">
        <div class="brand-logo">
          <span class="brand-mark">R</span>
          <span class="brand-name">RAGForge</span>
        </div>
        <h1 class="brand-headline">让 AI 读懂你的知识</h1>
        <p class="brand-sub">构建并检索你的知识库，为上层应用与 Agent 提供稳定、可追溯的知识接口。</p>
        <ul class="brand-bullets">
          <li>公共 / 个人 / 私有知识库，按权限隔离</li>
          <li>混合检索与重排，回答有据可依</li>
          <li>每一次调用留痕，结果可回溯</li>
        </ul>
      </div>
      <div class="brand-meta">面向个人与团队 · 数据按权限隔离</div>
    </aside>

    <main class="auth-panel">
      <div class="auth-panel-inner">
        <router-view />
      </div>
    </main>
  </div>
</template>

<style scoped>
.auth-screen {
  min-height: 100vh;
  display: grid;
  grid-template-columns: 1.05fr 1fr;
  background: #f6f8fb;
}

.auth-brand {
  background:
    radial-gradient(120% 90% at 18% 0%, rgba(56, 189, 248, 0.16), transparent 46%),
    radial-gradient(90% 80% at 92% 100%, rgba(20, 184, 166, 0.22), transparent 42%),
    linear-gradient(155deg, #0b1220 0%, #111c30 55%, #0c1626 100%);
  color: #fff;
  padding: 60px 56px;
  display: flex;
  flex-direction: column;
  justify-content: space-between;
  position: relative;
  overflow: hidden;
}

/* 细网格质感，向右淡出，避免与表单区生硬分界 */
.auth-brand::before {
  content: '';
  position: absolute;
  inset: 0;
  background-image:
    linear-gradient(rgba(148, 197, 253, 0.06) 1px, transparent 1px),
    linear-gradient(90deg, rgba(148, 197, 253, 0.06) 1px, transparent 1px);
  background-size: 46px 46px;
  mask-image: radial-gradient(120% 100% at 20% 18%, #000 0%, transparent 78%);
  pointer-events: none;
}

.brand-top,
.brand-meta {
  position: relative;
  z-index: 1;
}

.brand-logo {
  display: flex;
  align-items: center;
  gap: 12px;
  font-weight: 700;
  font-size: 20px;
}

.brand-name {
  letter-spacing: 0.2px;
}

.brand-mark {
  width: 34px;
  height: 34px;
  border-radius: 10px;
  background: linear-gradient(135deg, #38bdf8, #14b8a6);
  display: inline-flex;
  align-items: center;
  justify-content: center;
  font-size: 17px;
  font-weight: 800;
  box-shadow: 0 12px 30px rgba(20, 184, 166, 0.32);
}

.brand-headline {
  font-size: 32px;
  font-weight: 700;
  margin: 64px 0 0;
  line-height: 1.25;
  letter-spacing: 0.3px;
}

.brand-sub {
  color: #aebfd4;
  font-size: 14.5px;
  margin: 16px 0 0;
  max-width: 380px;
  line-height: 1.75;
}

.brand-bullets {
  list-style: none;
  margin: 36px 0 0;
  padding: 0;
}

.brand-bullets li {
  color: #dbe5f1;
  font-size: 13.5px;
  padding: 7px 0 7px 30px;
  position: relative;
  line-height: 1.5;
}

/* 勾选式标记，比小圆点更有产品质感 */
.brand-bullets li::before {
  content: '✓';
  position: absolute;
  left: 0;
  top: 6px;
  width: 20px;
  height: 20px;
  border-radius: 6px;
  font-size: 11px;
  font-weight: 800;
  color: #5eead4;
  background: rgba(45, 212, 191, 0.12);
  border: 1px solid rgba(45, 212, 191, 0.28);
  display: inline-flex;
  align-items: center;
  justify-content: center;
}

.brand-meta {
  color: #7f93ad;
  font-size: 12px;
  letter-spacing: 0.2px;
}

.auth-panel {
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 40px 24px;
  background: #fff;
}

.auth-panel-inner {
  width: 100%;
  max-width: 380px;
}

@media (max-width: 880px) {
  .auth-screen {
    grid-template-columns: 1fr;
  }

  .auth-brand {
    padding: 34px 26px 30px;
    min-height: auto;
  }

  .brand-headline {
    margin-top: 30px;
    font-size: 25px;
  }

  /* 移动端收起特性列表，登录表单优先 */
  .brand-bullets,
  .brand-meta {
    display: none;
  }

  .auth-panel {
    align-items: flex-start;
    padding: 28px 20px 48px;
  }
}
</style>
