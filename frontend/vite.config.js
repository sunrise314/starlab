import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import { fileURLToPath, URL } from 'node:url'

/**
 * Vite + Cesium 集成配置（starlab）
 *
 * 三大关键决策（沿用 digital-twin-platform 经验）：
 * 1. CESIUM_BASE_URL 指向 public/cesium/——静态资源一次性拷贝到 public/，
 *    不走 rollup-plugin-copy（Windows EBUSY 文件锁坑），开发/构建路径一致
 * 2. server proxy 把 /api 代理到后端 8090（starlab 后端端口），前端不写死端口
 * 3. resolve.alias '@' 指到 src，方便 import
 */
export default defineConfig({
  plugins: [vue()],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url))
    }
  },
  define: {
    // Cesium 会用这个 URL 加载 Workers/Assets/Widgets——指向 public/cesium 下的子目录
    CESIUM_BASE_URL: JSON.stringify('/cesium/')
  },
  server: {
    port: 5174,
    proxy: {
      '/api': {
        target: 'http://localhost:8090',
        changeOrigin: true
      }
    }
  },
  build: {
    // Cesium Workers 体积大，chunkSizeWarningLimit 放宽
    chunkSizeWarningLimit: 1000
  }
})
