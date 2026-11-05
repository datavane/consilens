// 分数展示的统一口径：后端已按 source+target 计算，UI 再兜一层，
// 避免脏数据（负数、>100、NaN）直接渲染给用户。
export function clampScore(score) {
  if (score === null || score === undefined) return null;
  const numeric = Number(score);
  if (!Number.isFinite(numeric)) return null;
  return Math.max(0, Math.min(100, numeric));
}

export function formatScore(score) {
  const clamped = clampScore(score);
  return clamped === null ? "—" : clamped.toFixed(1);
}
