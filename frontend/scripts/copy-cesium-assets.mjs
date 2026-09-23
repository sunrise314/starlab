/**
 * 将 Cesium 静态资源从 node_modules/cesium/Build/Cesium 拷贝到 public/cesium
 *
 * 这些资源包括：
 *   - Assets/      内置图标、纹理、星空
 *   - ThirdParty/  依赖（如 gltf-validator）
 *   - Widgets/     UI 组件资源
 *   - Workers/     Web Worker 文件（异步计算地形/几何）
 *
 * 沿用 digital-twin-platform 的方案：一次性拷贝到 public/cesium，
 * 走 Vite 静态托管，配 CESIUM_BASE_URL='/cesium/'，开发/构建路径一致。
 *
 * 运行时机：
 *   1. 首次 npm install 后运行一次
 *   2. 升级 cesium 版本后重新运行
 */

import { cpSync, existsSync, mkdirSync, rmSync } from 'node:fs'
import { dirname, join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const __dirname = dirname(fileURLToPath(import.meta.url))
const root = resolve(__dirname, '..')

const src = join(root, 'node_modules', 'cesium', 'Build', 'Cesium')
const dest = join(root, 'public', 'cesium')

const subDirs = ['Assets', 'ThirdParty', 'Widgets', 'Workers']

if (!existsSync(src)) {
  console.error('[copy-cesium] 错误: 找不到 node_modules/cesium/Build/Cesium')
  console.error('             请先运行 npm install 安装 cesium 依赖')
  process.exit(1)
}

// 清掉旧目录重建（避免残留过期文件）
if (existsSync(dest)) {
  console.log(`[copy-cesium] 清理旧目录: ${dest}`)
  rmSync(dest, { recursive: true, force: true })
}
mkdirSync(dest, { recursive: true })

for (const sub of subDirs) {
  const from = join(src, sub)
  const to = join(dest, sub)
  if (!existsSync(from)) {
    console.warn(`[copy-cesium] 警告: 源目录缺失 ${from}（跳过）`)
    continue
  }
  cpSync(from, to, { recursive: true })
  console.log(`[copy-cesium] 复制 ${sub}/  →  public/cesium/${sub}/`)
}

console.log('\n[copy-cesium] 完成。CESIUM_BASE_URL 已在 vite.config.js 中指向 /cesium/')
