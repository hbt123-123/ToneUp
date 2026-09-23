/**
 * 背题模式纯函数（§6.2 / EC-02）：
 * - mode 与 sessionKind 正交：review 会话强制 practice，bank 会话可切 recite
 * - recite 态下 ctx 强制 readonly/showAnswer/showAnalysis，submit 直接拦截
 * - 仅内存态，刷新/退出复位（不写 storage）
 */
export type PracticeMode = 'practice' | 'recite'

/**
 * 提交守卫：背题模式一律禁用提交（双保险，配合 ctx.readonly）
 * @param mode 当前练习模式
 * @returns 是否允许调用 submit()
 */
export function canSubmit(mode: PracticeMode): boolean {
  return mode !== 'recite'
}
