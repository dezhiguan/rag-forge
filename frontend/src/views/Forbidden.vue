<template>
  <div class="page-body forbidden-page">
    <section class="forbidden-panel">
      <div class="forbidden-code">403</div>
      <h1>无权访问</h1>
      <p>当前账号没有访问该页面的权限。</p>
      <router-link class="back-link" to="/">返回驾驶舱</router-link>
    </section>
  </div>
</template>

<style scoped>
.forbidden-page {
  min-height: calc(100vh - 44px);
  display: flex;
  align-items: center;
  justify-content: center;
}

.forbidden-panel {
  width: min(420px, 100%);
  text-align: center;
  padding: 28px;
  border: 1px solid var(--border);
  border-radius: 8px;
  background: #fff;
}

.forbidden-code {
  font-size: 48px;
  line-height: 1;
  font-weight: 800;
  color: #0f172a;
}

h1 {
  margin: 12px 0 4px;
  font-size: 22px;
  color: #0f172a;
}

p {
  margin: 0;
  color: #64748b;
  font-size: 14px;
}

.back-link {
  display: inline-flex;
  margin-top: 18px;
  color: #1d4ed8;
  font-weight: 600;
  text-decoration: none;
}
</style>
