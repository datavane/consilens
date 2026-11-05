// 任务状态 → badge 类型与中文文案
export const TASK_STATUS_MAP = {
  PENDING: { label: "待调度", type: "warning" },
  CLAIMED: { label: "已认领", type: "info" },
  RUNNING: { label: "运行中", type: "info" },
  CANCEL_REQUESTED: { label: "取消中", type: "warning" },
  SUCCEEDED: { label: "成功", type: "success" },
  FAILED: { label: "失败", type: "error" },
  RETRYABLE: { label: "可重试", type: "warning" },
  CANCELLED: { label: "已取消", type: "default" },
};

export const TASK_STATUS_OPTIONS = Object.entries(TASK_STATUS_MAP).map(
  ([value, item]) => ({ label: item.label, value })
);

// artifact 类型 → badge 类型与中文文案
export const ARTIFACT_TYPE_MAP = {
  CONFIG: { label: "配置", type: "info" },
  VALIDATION_RESULT: { label: "校验结果", type: "warning" },
  DIAGNOSIS: { label: "诊断报告", type: "warning" },
  REPAIR_CONFIG: { label: "修复配置", type: "info" },
  RUN_RESULT: { label: "运行结果", type: "success" },
};

export const ARTIFACT_TYPE_OPTIONS = Object.entries(ARTIFACT_TYPE_MAP).map(
  ([value, item]) => ({ label: item.label, value })
);

// 节点状态 → badge 类型与中文文案
export const NODE_STATUS_MAP = {
  ONLINE: { label: "在线", type: "success" },
  OFFLINE: { label: "离线", type: "default" },
  REGISTERED: { label: "已注册", type: "warning" },
};

// AI 会话状态 → badge 类型与中文文案（业务页 AI 助手抽屉使用）
export const AI_SESSION_STATUS_MAP = {
  READY: { label: "就绪", type: "default" },
  RUNNING: { label: "运行中", type: "success" },
  WAITING_SECRET: { label: "等待凭据", type: "warning" },
  WAITING_APPROVAL: { label: "等待审批", type: "warning" },
  WAITING_INPUT: { label: "等待输入", type: "warning" },
  SUSPENDED: { label: "已暂停", type: "warning" },
  COMPLETED: { label: "已完成", type: "success" },
  FAILED: { label: "失败", type: "error" },
  CANCELLED: { label: "已取消", type: "default" },
};

// 差异操作类型（与后端 DiffOperation 一致）
// SOURCE_MISSING：源端缺失 → 目标端存在该行；TARGET_MISSING：目标端缺失 → 源端已删除
export const DIFF_OPERATION_MAP = {
  MISMATCH: { label: "更新", type: "warning" },
  SOURCE_MISSING: { label: "源缺失", type: "success" },
  TARGET_MISSING: { label: "目标缺失", type: "error" },
};

// 后端差异明细使用小写操作键（mismatch / source_missing / target_missing）：
// 表格、图例、抽屉标题统一从此表取 value/label/type，避免各处各写一份文案
export const DIFF_OPERATION_LABELS = Object.fromEntries(
  Object.entries(DIFF_OPERATION_MAP).map(([value, meta]) => {
    const key = value.toLowerCase();
    return [key, { value: key, ...meta }];
  })
);

// 差异评分色阶（diff-report / diffSummary.status）
export const DIFF_SCORE_STATUS_MAP = {
  EXCELLENT: { label: "完全一致", type: "success" },
  GOOD: { label: "少量差异", type: "warning" },
  POOR: { label: "差异较多", type: "error" },
  NONE: { label: "暂无数据", type: "default" },
};

// 所有状态映射的合并视图：StateTag 用它在单一来源上查 label/type，
// 避免组件内再维护一份会与上述映射漂移的内置表
export const STATE_TAG_MAP = {
  ...TASK_STATUS_MAP,
  ...ARTIFACT_TYPE_MAP,
  ...NODE_STATUS_MAP,
  ...DIFF_OPERATION_MAP,
  ...DIFF_SCORE_STATUS_MAP,
};

// badge 类型 → 语义色（引用 global.scss 的 CSS 变量，主题切换自动跟随；页面禁止另写 hex）
export const STATUS_TYPE_COLORS = {
  success: "var(--success)",
  warning: "var(--accent-amber)",
  error: "var(--danger)",
  info: "var(--primary)",
  primary: "var(--primary)",
  default: "var(--text-muted)",
};

export function statusTypeColor(type) {
  return STATUS_TYPE_COLORS[type] || STATUS_TYPE_COLORS.default;
}

// 数据源类型 → 图标色（仅后端 GET /v1/datasources/types 支持的类型）
export const DS_TYPE_COLORS = {
  mysql: "var(--primary)",
  doris: "var(--accent-amber)",
  hive: "var(--accent-green)",
  postgresql: "var(--violet)",
  clickhouse: "var(--danger)",
};

// 未知类型回退到中性色（列表必须能渲染未知类型的行）
export function datasourceTypeColor(type) {
  return DS_TYPE_COLORS[String(type || "").toLowerCase()] || "var(--text-muted)";
}
