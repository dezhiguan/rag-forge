<template>
  <div class="auth-screen">
    <aside class="auth-brand" aria-label="RAGForge">
      <div class="brand-top">
        <div class="brand-logo">
          <span class="brand-mark">R</span>
          <span class="brand-name">RAGForge</span>
        </div>
        <div class="brand-headline">企业级 RAG 知识引擎</div>
        <div class="brand-sub">检索、评测、审计与多租户权限，为上层 Agent 提供生产级知识接口。</div>
        <ul class="brand-bullets">
          <li>公共 / 个人 / 系统 KB 三级隔离</li>
          <li>单 aud Token 与会话撤销闭环</li>
          <li>检索全链路审计可回溯</li>
        </ul>
      </div>
      <div class="brand-meta">v1.0 · 仅限授权管理员访问</div>
    </aside>

    <main class="auth-panel">
      <router-view />
    </main>
  </div>
</template>

<style scoped>
.auth-screen {
  min-height: 100vh;
  display: grid;
  grid-template-columns: 1.1fr 1fr;
  background: #f8fafc;
}

.auth-brand {
  background:
    linear-gradient(160deg, rgba(15, 23, 42, 0.96), rgba(30, 41, 59, 0.94)),
    radial-gradient(circle at 72% 82%, rgba(6, 182, 212, 0.35), transparent 34%);
  color: #fff;
  padding: 56px 48px;
  display: flex;
  flex-direction: column;
  justify-content: space-between;
  position: relative;
  overflow: hidden;
}

.auth-brand::before {
  content: '';
  position: absolute;
  inset: 0;
  background-image:
    linear-gradient(rgba(255, 255, 255, 0.05) 1px, transparent 1px),
    linear-gradient(90deg, rgba(255, 255, 255, 0.05) 1px, transparent 1px);
  background-size: 42px 42px;
  mask-image: linear-gradient(90deg, #000, transparent 88%);
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
  letter-spacing: 0;
}

.brand-mark {
  width: 32px;
  height: 32px;
  border-radius: 8px;
  background: linear-gradient(135deg, #0ea5e9, #14b8a6);
  display: inline-flex;
  align-items: center;
  justify-content: center;
  font-size: 16px;
  font-weight: 800;
  box-shadow: 0 10px 28px rgba(14, 165, 233, 0.28);
}

.brand-headline {
  font-size: 28px;
  font-weight: 700;
  margin-top: 58px;
  line-height: 1.25;
  letter-spacing: 0;
}

.brand-sub {
  color: #cbd5e1;
  font-size: 14px;
  margin-top: 14px;
  max-width: 360px;
  line-height: 1.7;
}

.brand-bullets {
  list-style: none;
  margin: 30px 0 0;
  padding: 0;
}

.brand-bullets li {
  color: #e2e8f0;
  font-size: 13px;
  padding: 5px 0 5px 16px;
  position: relative;
}

.brand-bullets li::before {
  content: '';
  position: absolute;
  left: 0;
  top: 15px;
  width: 5px;
  height: 5px;
  border-radius: 50%;
  background: #14b8a6;
}

.brand-meta {
  color: #94a3b8;
  font-size: 12px;
}

.auth-panel {
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 40px 24px;
  background: #fff;
}

@media (max-width: 880px) {
  .auth-screen {
    grid-template-columns: 1fr;
  }

  .auth-brand {
    padding: 32px 24px 36px;
    min-height: 280px;
  }

  .brand-headline {
    margin-top: 28px;
    font-size: 24px;
  }

  .auth-panel {
    align-items: flex-start;
    padding: 28px 20px 48px;
  }
}
</style>
