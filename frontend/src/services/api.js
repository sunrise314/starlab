import axios from 'axios'

/**
 * starlab 后端 API 封装——对接 OrbitController
 *
 * 6 个端点（后端端口 8090，dev 通过 vite proxy 转发）：
 *  1. GET /api/constellation                      星座概览
 *  2. GET /api/satellites                          所有卫星实时位置
 *  3. GET /api/satellites/{id}                     单星位置
 *  4. GET /api/ground-stations                     所有地面站
 *  5. GET /api/links                               当前可见链路（仰角>10°）
 *  6. GET /api/ground-stations/{id}/visibility     指定地面站对所有卫星的可见性
 */
const api = axios.create({
  baseURL: '',
  timeout: 10000
})

api.interceptors.response.use(
  response => response.data,
  error => {
    console.error('[starlab API] 调用失败:', error.message)
    return Promise.reject(error)
  }
)

// ── 星座概览 ──

/** 星座构型 + 卫星列表 */
export function getConstellation() {
  return api.get('/api/constellation')
}

// ── 卫星 ──

/** 12 颗卫星实时位置（ECI/ECEF/LLA/速度） */
export function getSatellites() {
  return api.get('/api/satellites')
}

/** 单颗卫星实时位置 */
export function getSatellite(id) {
  return api.get(`/api/satellites/${id}`)
}

// ── 地面站 ──

/** 5 个地面站 */
export function getGroundStations() {
  return api.get('/api/ground-stations')
}

// ── 链路 ──

/** 当前可见链路（仅返回 visible=true 的） */
export function getVisibleLinks() {
  return api.get('/api/links')
}

/**
 * 指定地面站对所有卫星的可见性
 * @param {string} stationId 地面站 ID（BJO/SHO/SYO/KSO/MHO）
 */
export function getStationVisibility(stationId) {
  return api.get(`/api/ground-stations/${stationId}/visibility`)
}

export default api

// ── STCN 切换仿真 ──

/**
 * 运行一次切换仿真（后端内部循环，毫秒级返回）
 * @param {Object} params
 * @param {number} params.durationSec 仿真时长（秒），默认 600，最大 7200
 * @param {number} params.tickSec     tick 步长（秒），默认 1
 * @param {string} [params.algorithm] 算法：greedy / predictive，默认 greedy
 * @param {string} [params.start]     ISO 时间，null = now()
 */
export function simulateHandover(params = {}) {
  return api.post('/api/handover/simulate', {
    durationSec: params.durationSec ?? 600,
    tickSec: params.tickSec ?? 1,
    algorithm: params.algorithm || 'greedy',
    start: params.start || null
  })
}

/**
 * 双策略对比仿真——同时跑贪心 + 预测式，返回各自统计 + 改进量
 * @param {Object} params
 * @param {number} params.durationSec
 * @param {number} params.tickSec
 */
export function compareHandover(params = {}) {
  return api.post('/api/handover/compare', {
    durationSec: params.durationSec ?? 600,
    tickSec: params.tickSec ?? 1
  })
}

/**
 * 跑仿真并返回每个 tick 的快照（用于时间轴回放）
 * @param {Object} params
 * @param {number} params.durationSec 仿真时长（秒），默认 3600
 * @param {number} params.tickSec     tick 步长（秒），默认 10
 * @param {string} [params.algorithm] greedy / predictive
 * @returns {Promise<{timeline: Array, events: Array, totalTicks: number, durationSec: number, tickSec: number, ...}>}
 */
export function fetchTimeline(params = {}) {
  return api.post('/api/handover/timeline', {
    durationSec: params.durationSec ?? 3600,
    tickSec: params.tickSec ?? 10,
    algorithm: params.algorithm || 'greedy',
    start: params.start || null
  })
}

/**
 * 重新配置星座参数（动态 Walker）
 * @param {Object} params
 * @param {number} params.total        卫星总数 T
 * @param {number} params.planes      轨道面 P
 * @param {number} params.inclination 倾角 (度)
 * @param {number} params.altitudeKm 轨道高度 (km)
 * @param {number} params.phasingF    Walker F 参数 (0~T-1)
 */
export function configureConstellation(params) {
  return api.post('/api/constellation/configure', params)
}

/** 恢复默认星座 Walker 12/3/1 53°/550km */
export function resetConstellation() {
  return api.post('/api/constellation/reset')
}

/** 查询环形缓冲中的最近切换事件 */
export function getHandoverEvents(limit = 50) {
  return api.get('/api/handover/events', { params: { limit } })
}

/** 清空环形缓冲 */
export function clearHandoverEvents() {
  return api.delete('/api/handover/events')
}

// ── 干扰分析（方向5） ──

/**
 * 分析当前时刻目标卫星与地面站链路的同频/邻信道/宽带噪声干扰
 * @param {Object} params 全可选
 * @param {string} [params.targetSatelliteId] 目标卫星 ID（缺省=所有卫星）
 * @param {string} [params.stationId]         地面站 ID（缺省=所有地面站）
 * @param {string} [params.start]            ISO 时间（缺省=now()）
 * @returns {Promise<{analyzedAt, targetSatelliteId, stationId, statistics, results}>}
 */
export function analyzeInterference(params = {}) {
  return api.post('/api/interference/analyze', {
    targetSatelliteId: params.targetSatelliteId || null,
    stationId: params.stationId || null,
    start: params.start || null
  })
}

// ── 星间链路 ISL（方向6） ──

/**
 * 当前 ISL 拓扑（卫星间可见且距离 ≤ maxDistanceKm 的链路）
 * @param {Object} params
 * @param {string} [params.start]         ISO 时间，缺省=now()
 * @param {number} [params.maxDistanceKm] ISL 最大作用距离，默认 5000
 * @returns {Promise<{analyzedAt, statistics, adjacency, degrees, links}>}
 */
export function getIslTopology(params = {}) {
  return api.post('/api/isl/topology', {
    start: params.start || null,
    maxDistanceKm: params.maxDistanceKm ?? null
  })
}

/**
 * 多跳最短时延路由（Dijkstra）
 * @param {Object} params
 * @param {string} [params.sourceSatelliteId] 与 sourceStationId 二选一
 * @param {string} [params.sourceStationId]   与 sourceSatelliteId 二选一
 * @param {string} params.destinationSatelliteId 必填
 * @param {string} [params.start]             ISO 时间
 * @param {number} [params.maxDistanceKm]     默认 5000
 */
export function computeIslRoute(params) {
  return api.post('/api/isl/route', {
    sourceSatelliteId: params.sourceSatelliteId || null,
    sourceStationId: params.sourceStationId || null,
    destinationSatelliteId: params.destinationSatelliteId,
    start: params.start || null,
    maxDistanceKm: params.maxDistanceKm ?? null
  })
}
