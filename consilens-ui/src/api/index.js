import axios from "axios";

const request = axios.create({
  baseURL: "/v1",
  timeout: 30000,
  // 数组参数序列化为多值形式（status=A&status=B），匹配 Spring @RequestParam List<T>
  paramsSerializer: { indexes: null },
});

// 由 App.vue 在挂载后注入 message 实例（naive-ui）
let messageApi = null;
export function setupMessage(message) {
  messageApi = message;
}

// silent: 完全静默；silentStatuses: 仅对这些 HTTP 状态码静默（例如可幂等处理的 404）
// 相同文案在短时间内只弹一次：断网时轮询每 2 秒失败一次，逐次弹窗会把屏幕刷满；
// 调用方自己再弹一次时也会命中这里（拦截器与页面各弹一次 = 双弹窗）。
let lastNotifyText = "";
let lastNotifyAt = 0;
const NOTIFY_DEDUPE_MS = 1500;

function notify(error, config) {
  if (!messageApi || config?.silent) return;
  const silentStatuses = config?.silentStatuses;
  if (Array.isArray(silentStatuses) && silentStatuses.includes(error.status)) return;
  const text = error.message || "请求失败";
  const now = Date.now();
  if (text === lastNotifyText && now - lastNotifyAt < NOTIFY_DEDUPE_MS) return;
  lastNotifyText = text;
  lastNotifyAt = now;
  messageApi.error(text);
}

request.interceptors.request.use((config) => {
  const apiKey = localStorage.getItem("consilens-api-key");
  if (apiKey) {
    config.headers["X-Consilens-Api-Key"] = apiKey;
  }
  return config;
});

request.interceptors.response.use(
  (response) => {
    const body = response.data;
    if (body && body.success === false) {
      const error = new Error(body.error || body.errorCode || "request failed");
      error.errorCode = body.errorCode;
      error.traceId = body.traceId;
      error.raw = body;
      error.status = response.status;
      notify(error, response.config);
      error.notified = true;
      return Promise.reject(error);
    }
    return body && typeof body === "object" && "data" in body ? body.data : body;
  },
  (error) => {
    const status = error.response?.status;
    let message = error.message || "网络请求失败";
    if (status === 404) {
      message = "资源不存在";
    } else if (status >= 500) {
      message = "服务器内部错误";
    } else if (!error.response) {
      message = "无法连接到服务器";
    }
    const normalized = new Error(error.response?.data?.error || message);
    normalized.status = status;
    normalized.errorCode = error.response?.data?.errorCode;
    // 保留原始 response：调用方常用 error.response.data.error 取后端原因，
    // 之前的归一化对象没有这个字段，兜底取值恒为空，页面只能弹通用文案。
    normalized.response = error.response;
    // 拦截器已经弹过一次后端原因；调用方再弹一次就会出现"双弹窗"，用这个标记区分
    normalized.notified = true;
    notify(normalized, error.config);
    return Promise.reject(normalized);
  }
);

export default request;
