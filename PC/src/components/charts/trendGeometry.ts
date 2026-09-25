import type { DailyTrendPoint } from './types'

/** 默认视口尺寸与留白（与原 StatsView 内联 SVG 的 320x140 几何一致） */
export const TREND_VIEW_W = 320
export const TREND_VIEW_H = 140

const PAD_X = 10
const PAD_Y = 10

export interface TrendGeometry {
  /** false 表示无折线可画（点数 < 2），组件应渲染"暂无数据"占位 */
  hasLine: boolean
  /** 作答量折线的 polyline points */
  attemptsPoints: string
  /** 正确率折线的 polyline points（点数 < 2 时为 null） */
  accuracyPoints: string | null
}

/**
 * 纯映射：DailyTrendPoint[] → SVG 几何（无任何 IO，便于独立验证）。
 * 作答量按题数线性映射绘图区（夹取到绘图区内）；正确率按 0~1 线性映射同一绘图区。
 */
export function buildTrendGeometry(
  points: DailyTrendPoint[],
  width = TREND_VIEW_W,
  height = TREND_VIEW_H,
): TrendGeometry {
  if (points.length < 2) {
    return { hasLine: false, attemptsPoints: '', accuracyPoints: null }
  }
  const plotH = height - PAD_Y * 2
  const baseline = height - PAD_Y
  const step = (width - PAD_X * 2) / (points.length - 1)
  const x = (i: number): number => PAD_X + i * step
  // M-373：correct_rate 按契约为非空 number（types.ts「空天为 0」），
  // 原 hasAccuracy 判空恒为 true 属死逻辑，移除后正确率折线随折线绘制恒可产出
  return {
    hasLine: true,
    // M-374：作答量下界同样夹取到 0，负值/异常输入不再把折线点映射到基线之下
    attemptsPoints: points
      .map((p, i) => `${x(i)},${baseline - Math.max(0, Math.min(plotH, p.attempts))}`)
      .join(' '),
    // M-374：正确率夹取到 [0, 1]，越界输入不越出绘图区
    accuracyPoints: points
      .map((p, i) => `${x(i)},${baseline - Math.max(0, Math.min(1, p.correct_rate)) * plotH}`)
      .join(' '),
  }
}