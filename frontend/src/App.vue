<script setup>
import Scene3D from './views/Scene3D.vue'
</script>

<template>
  <div class="layout-root">
    <header class="app-header">
      <div class="logo">
        <span class="logo-icon">🛰️</span>
        星地智连仿真系统 · Starlab
      </div>
      <div class="header-info">
        Walker-12 星座 · 3 面 × 4 星 · 53° / 550km · 实时轨道传播
      </div>
    </header>
    <main class="layout-content">
      <Scene3D />
    </main>
  </div>
</template>

<style>
.app-header {
  height: 48px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 20px;
  background: linear-gradient(90deg, #0a0e1a 0%, #16213e 100%);
  border-bottom: 1px solid rgba(255, 255, 255, 0.08);
  z-index: 100;
}
.logo {
  font-size: 15px;
  font-weight: 600;
  color: var(--accent-green);
  display: flex;
  align-items: center;
  gap: 8px;
}
.logo-icon {
  font-size: 18px;
}
.header-info {
  font-size: 12px;
  color: rgba(255, 255, 255, 0.5);
}
.layout-root {
  display: flex;
  flex-direction: column;
  height: 100vh;
}
.layout-content {
  flex: 1;
  position: relative;
  overflow: hidden;
}
</style>
