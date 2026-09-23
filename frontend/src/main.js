import { createApp } from 'vue'
import './style.css'
import 'cesium/Build/Cesium/Widgets/widgets.css'
import App from './App.vue'

/**
 * Cesium + Vue 3 入口（starlab）
 *
 * 关键：引入 Cesium 的 widgets.css——这个 CSS 定义了 Viewer 右上角的
 * 各种按钮（场景切换/全屏/信息框等）的样式，不引入的话按钮会显示异常。
 */
createApp(App).mount('#app')
