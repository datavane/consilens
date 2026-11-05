// ===== 数据源动态表单公共逻辑 =====

// 新建数据源时的默认类型（后端类型列表不可用时的兜底）
export const DEFAULT_DS_TYPE = "mysql";

// 后端连接参数白名单（超出部分不提交，避免后端拒绝）
export const DS_PARAM_WHITELIST = [
  "host",
  "port",
  "database",
  "username",
  "password",
  "sid",
  "schema",
  "properties",
];

// 后端模板接口不可用时的兜底模板（与旧的硬编码字段一致）
export const FALLBACK_DS_FIELDS = [
  { field: "host", title: "主机", type: "input", placeholder: "IP 或主机名", required: true },
  { field: "port", title: "端口", type: "number", placeholder: "端口", required: false },
  { field: "database", title: "数据库", type: "input", placeholder: "数据库 / schema / service name", required: false },
  { field: "username", title: "用户名", type: "input", placeholder: "", required: false },
  { field: "password", title: "密码", type: "input", placeholder: "", required: false },
];

// 从表单值中收集连接参数：空字符串 / 空值 / undefined 一律不提交，统一转字符串
export function collectDsParam(values, fields) {
  const param = {};
  for (const f of fields || []) {
    if (!DS_PARAM_WHITELIST.includes(f.field)) continue;
    const raw = values?.[f.field];
    if (raw === undefined || raw === null || raw === "") continue;
    param[f.field] = String(raw);
  }
  return param;
}

// 从参数中拆出测试连接专用的 options（sid / schema / properties）
export function splitTestOptions(param) {
  const options = {};
  for (const key of ["sid", "schema", "properties"]) {
    if (param[key] !== undefined) {
      options[key] = param[key];
      delete param[key];
    }
  }
  return Object.keys(options).length ? options : undefined;
}
