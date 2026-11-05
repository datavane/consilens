<script setup>
import { ref, reactive, computed, onMounted, onBeforeUnmount, h, watch, nextTick } from "vue";
import { useRoute, useRouter } from "vue-router";
import { useMessage } from "naive-ui";
import { NButton, NIcon, NPopconfirm, NSpin, NSwitch, NAlert } from "naive-ui";
import { ArrowBackOutline, RefreshOutline, TimeOutline, ServerOutline, PlayCircleOutline, PulseOutline } from "@vicons/ionicons5";
import { getTaskInstance, retryTask, cancelTask, getDiffReport, listArtifactDifferences, getTaskLogs, downloadTaskLog, getArtifactContent } from "@/api/modules";
import { formatTime, formatDuration, formatNumber, formatRatio } from "@/utils/format";
import { DIFF_OPERATION_LABELS } from "@/common/statusMapping";
import StateTag from "@/components/common/StateTag.vue";
import KeyValueList from "@/components/common/KeyValueList.vue";
import MatchScoreGauge from "@/components/common/MatchScoreGauge.vue";
import ValueDiffTable from "@/components/common/ValueDiffTable.vue";
import ColumnDiffTable from "@/components/common/ColumnDiffTable.vue";
import EmptyState from "@/components/common/EmptyState.vue";
import CopyableId from "@/components/common/CopyableId.vue";

const route = useRoute();
const router = useRouter();
const message = useMessage();

const taskId = route.params.taskId;
const loading = ref(true);
const task = ref(null);
// 区分"加载失败"与"任务确实不存在"：一次网络抖动不能把页面永久显示成不存在
const loadError = ref(false);
let pollTimer = null;

// 运行中横幅的「已运行」计时：跟随轮询节拍刷新
const nowTick = ref(Date.now());

const FINAL_STATUSES = ["SUCCEEDED", "FAILED", "CANCELLED", "RETRYABLE"];
const TABS = ["overview", "diff", "columns", "logs"];

function ensureValidTab(tab) {
  return TABS.includes(tab) ? tab : "overview";
}

const activeTab = ref(ensureValidTab(route.query.tab));

// 轮询与用户操作（取消/重试）会并发发请求：只认最后一次发起的响应，
// 否则取消后返回的旧快照会把"已取消"覆盖回"运行中"。
let taskRequestSeq = 0;

async function loadTask(showLoading = false) {
  if (showLoading) loading.value = true;
  const seq = ++taskRequestSeq;
  try {
    const snapshot = await getTaskInstance(taskId);
    if (seq !== taskRequestSeq) return;
    task.value = snapshot;
    loadError.value = false;
    const status = task.value?.status;
    if (FINAL_STATUSES.includes(status)) stopPolling();
    loadClusterInfo();
  } catch (error) {
    if (seq !== taskRequestSeq) return;
    // 只有 404 才是"任务不存在"；其它错误保持轮询，避免一次抖动后页面永久冻结
    if (error?.status === 404 || error?.response?.status === 404) {
      stopPolling();
      task.value = null;
      loadError.value = false;
    } else {
      loadError.value = true;
    }
  } finally {
    if (seq === taskRequestSeq) loading.value = false;
  }
}

// ===== 集群执行信息（YARN/K8s）=====
// 集群应用 ID / tracking URL 存在 RUN_RESULT artifact 内容里；按 artifactId 去重，
// 轮询期间不重复拉取，拉取失败不影响主流程。
const clusterInfo = ref(null);
let loadedClusterArtifactId = null;

async function loadClusterInfo() {
  const artifact = (task.value?.artifacts || []).find((a) => a.artifactType === "RUN_RESULT");
  if (!artifact || loadedClusterArtifactId === artifact.artifactId) return;
  loadedClusterArtifactId = artifact.artifactId;
  try {
    const content = await getArtifactContent(artifact.artifactId, { silent: true });
    const parsed = typeof content?.content === "string" ? JSON.parse(content.content) : content?.content;
    if (parsed?.clusterApplicationId || parsed?.finalStatus) {
      clusterInfo.value = {
        clusterApplicationId: parsed.clusterApplicationId,
        finalStatus: parsed.finalStatus,
        trackingUrl: parsed.trackingUrl,
      };
    }
  } catch {
    // 集群信息仅用于展示：失败后清空去重标记，下一轮轮询可以重试，
    // 否则一次网络抖动会让集群卡片永久消失。
    loadedClusterArtifactId = null;
  }
}

function startPolling() {
  stopPolling();
  pollTimer = setInterval(() => {
    loadTask();
    nowTick.value = Date.now();
  }, 2000);
}

function stopPolling() {
  if (pollTimer) {
    clearInterval(pollTimer);
    pollTimer = null;
  }
}

async function handleRetry() {
  await retryTask(taskId);
  message.success("已发起重试");
  await loadTask();
  // 重试后任务重新进入运行态：恢复轮询，否则页面会一直停在旧终态
  startPolling();
  // 日志定时器在终态时被停掉了；这里不恢复的话，自动刷新开关显示"开"但内容不再更新
  if (activeTab.value === "logs" && logState.autoRefresh) startLogPolling();
}

async function handleCancel() {
  await cancelTask(taskId);
  message.success("已发起取消");
  loadTask();
}

const status = computed(() => task.value?.status || "");
const nextActions = computed(() => task.value?.availableNextActions || []);
// 失败原因由任务查询接口透出；CANCELLED 也会写 errorCode，不能当作执行失败展示
const failureCode = computed(() => task.value?.errorCode || "");
const failureMessage = computed(() => task.value?.errorMessage || "");
const hasFailure = computed(() =>
  Boolean((failureCode.value || failureMessage.value)
    && ["FAILED", "RETRYABLE"].includes(status.value))
);
const failureTitle = computed(() => (status.value === "RETRYABLE" ? "执行失败（可重试）" : "执行失败"));

// 运行中任务的已运行时长（从开始时间起算，缺省用提交时间）
const runningElapsed = computed(() => {
  const start = task.value?.startTime || task.value?.submitTime;
  if (!start) return "";
  return formatDuration(start, nowTick.value);
});

// ===== Tab 切换与深链 =====
function handleTabChange(tab) {
  activeTab.value = tab;
  router.replace({ query: { ...route.query, tab } });
  if (tab === "diff" || tab === "columns") loadDiffReport();
  if (tab === "logs") {
    loadLogs(true);
    startLogPolling();
  } else {
    stopLogPolling();
  }
}

watch(
  () => route.query.tab,
  (tab) => {
    if (tab) activeTab.value = ensureValidTab(tab);
  }
);

// ===== diff-report =====
const diffReport = ref(null);
const diffReportLoading = ref(false);
let diffReportLoaded = false;

const diffReportError = ref(false);

async function loadDiffReport() {
  if (diffReportLoaded || status.value !== "SUCCEEDED") return;
  diffReportLoading.value = true;
  try {
    diffReport.value = await getDiffReport(taskId, { silent: true });
    diffReportLoaded = true;
    diffReportError.value = false;
  } catch {
    // 静默失败但不装成"没有差异"：报告拉取失败和"确实没有差异"是两件事，
    // 前者会让用户以为比对通过
    diffReport.value = null;
    diffReportLoaded = false;
    diffReportError.value = true;
  } finally {
    diffReportLoading.value = false;
  }
}

watch(
  () => task.value?.status,
  (s) => {
    if (s === "SUCCEEDED") loadDiffReport();
    // 任务结束后补拉一次日志，确保最后几行（结束标记）不丢；同时停掉日志轮询
    if (FINAL_STATUSES.includes(s)) {
      stopLogPolling();
      // 末次轮询可能还在飞：直接 return 会把"结束标记"那几行永久丢掉。
      // 在途时登记一次补拉，等当前请求结束后再取一次增量。
      if (activeTab.value === "logs") {
        if (logState.loading) pendingLogTail = true;
        else loadLogs(false);
      }
    }
  }
);

const hasDiffData = computed(
  () => diffReport.value !== null && diffReport.value !== undefined && diffReport.value.status !== "NONE"
);

// ===== 差异明细分页 =====
const diffPage = reactive({
  page: 1,
  pageSize: 10,
  total: 0,
  truncated: false,
  items: [],
  loading: false,
  loaded: false,
});

// 操作类型筛选（图例勾选框，多选；默认全选 = 全部类型，取消勾选即只看其余类型）
const FILTER_OPS = Object.values(DIFF_OPERATION_LABELS);
const diffOps = ref(Object.keys(DIFF_OPERATION_LABELS));
// 差异明细列显示开关：默认只看差异列
const showAllColumns = ref(false);

// 差异明细分页请求序号：快速切换筛选/翻页时只应用最后一次响应
let diffRequestSeq = 0;

async function loadDiffPage(page = 1) {
  const requestSeq = ++diffRequestSeq;
  const runResult = (task.value?.artifacts || []).find((item) => item.artifactType === "RUN_RESULT");
  if (!runResult) return;
  // 全部类型都未勾选时不返回数据
  if (diffOps.value.length === 0) {
    diffPage.items = [];
    diffPage.total = 0;
    diffPage.truncated = false;
    diffPage.page = page;
    diffPage.loaded = true;
    diffPage.loading = false;
    return;
  }
  diffPage.loading = true;
  try {
    const result = await listArtifactDifferences(runResult.artifactId, {
      offset: (page - 1) * diffPage.pageSize,
      limit: diffPage.pageSize,
      // 始终显式传差异类型：旧 artifact 的差异文件可能混有历史抽样行
      // （被截断在 1000 上限外会挤掉真实差异行），不能依赖无过滤请求。
      operation: diffOps.value.join(","),
    });
    if (requestSeq !== diffRequestSeq) return;
    diffPage.items = result.items || [];
    diffPage.total = result.total ?? diffPage.items.length;
    diffPage.truncated = !!result.truncated;
    diffPage.page = page;
    diffPage.loaded = true;
  } catch (error) {
    if (requestSeq !== diffRequestSeq) return;
    message.error(error?.response?.data?.error || "获取差异明细失败");
  } finally {
    if (requestSeq === diffRequestSeq) {
      diffPage.loading = false;
    }
  }
}

function handleDiffOpsChange(ops) {
  diffOps.value = ops;
  diffPage.page = 1;
  // 保留当前数据直到新数据返回（勾选栏与表格不闪空）
  loadDiffPage(1);
}

// 切换勾选并返回新数组（由 handleDiffOpsChange 统一处理）
function toggleOp(value) {
  const cur = [...diffOps.value];
  const index = cur.indexOf(value);
  if (index >= 0) {
    cur.splice(index, 1);
  } else {
    cur.push(value);
  }
  return cur;
}

function handleDiffPageChange(page) {
  loadDiffPage(page);
}

function handleDiffPageSizeChange(pageSize) {
  diffPage.pageSize = pageSize;
  diffPage.page = 1;
  loadDiffPage(1);
}

// 任务成功且 diff 报告可用时加载第一页
watch(
  () => hasDiffData.value,
  (ready) => {
    if (ready && !diffPage.loaded) loadDiffPage(1);
  }
);

const statistics = computed(() => diffReport.value?.statistics || null);

// ===== 指标 =====
const diffBreakdown = computed(() => {
  const s = statistics.value;
  if (!s) return [];
  return [
    { label: "新增", value: s.sourceMissingCount ?? 0, color: "var(--success)" },
    { label: "删除", value: s.targetMissingCount ?? 0, color: "var(--danger)" },
    { label: "更新", value: s.mismatchCount ?? 0, color: "var(--warning)" },
  ];
});

const diffPercent = computed(() => statistics.value?.differencePercentage ?? null);

const metrics = computed(() => {
  const s = statistics.value;
  if (!s) return [];
  return [
    { label: "源表行数", value: formatNumber(s.sourceRowCount), extra: "source rows" },
    { label: "目标表行数", value: formatNumber(s.targetRowCount), extra: "target rows" },
    { label: "未变化行", value: formatNumber(s.unchangedCount), extra: "unchanged" },
    { label: "比对耗时", value: s.processingTimeMs != null ? `${s.processingTimeMs} ms` : "-", extra: "processing time" },
  ];
});

// ===== 概览：基本信息 =====
const overviewItems = computed(() => [
  { label: "任务 ID", value: task.value?.taskId, copyable: true, mono: true },
  { label: "序号", value: task.value?.serialNo, mono: true },
  { label: "类型", value: task.value?.taskType },
  { label: "Trace ID", value: task.value?.traceId, copyable: true, mono: true },
  { label: "执行节点", value: task.value?.executeNodeKey || "-" },
  { label: "重试次数", value: task.value?.retryCount ?? 0 },
  { label: "提交时间", value: formatTime(task.value?.submitTime) },
  { label: "开始时间", value: formatTime(task.value?.startTime) },
  { label: "结束时间", value: formatTime(task.value?.endTime) },
  { label: "耗时", value: formatDuration(task.value?.startTime, task.value?.endTime) },
]);

const clusterItems = computed(() => [
  { label: "集群应用 ID", value: clusterInfo.value?.clusterApplicationId || "-", copyable: true, mono: true },
  { label: "应用终态", value: clusterInfo.value?.finalStatus || "-" },
]);

// tracking URL 来自集群侧返回值：只允许 http(s)，避免 javascript: 之类的协议被渲染成可点击链接
const trackingUrl = computed(() => {
  const url = clusterInfo.value?.trackingUrl;
  return typeof url === "string" && /^https?:\/\//i.test(url) ? url : "";
});

const diffNotReady = computed(() => status.value !== "SUCCEEDED");

function copyText(text) {
  navigator.clipboard?.writeText(text);
  message.success("已复制");
}

// ===== 运行日志 =====
// 首次进入读文件末尾（长任务不必从第 1 行拉），之后按 nextOffset 增量追加。
const LOG_TAIL_LINES = 2000;
const LOG_PAGE_LINES = 2000;
// 前端保留的最大字符数：超出后丢弃最早内容，避免长时间运行把页面内存撑爆
const LOG_MAX_CHARS = 400_000;

const logState = reactive({
  content: "",
  offset: 0,
  available: false,
  fileSize: 0,
  lastModified: null,
  loading: false,
  loaded: false,
  error: "",
  autoRefresh: true,
});
const logViewer = ref(null);
let logTimer = null;
// 轮询请求在途时用户点“刷新”：不能直接丢弃，等当前请求结束后补一次完整重载
let pendingLogReset = false;
// 任务进入终态时若日志请求仍在途，登记一次补拉（见 status watcher）
let pendingLogTail = false;

async function loadLogs(reset = false) {
  if (logState.loading) {
    if (reset) pendingLogReset = true;
    return;
  }
  logState.loading = true;
  try {
    const initial = reset || !logState.loaded;
    const stickToBottom = initial || isLogNearBottom();
    const page = initial
      ? await getTaskLogs(taskId, { tailLines: LOG_TAIL_LINES })
      : await getTaskLogs(taskId, { offset: logState.offset, limit: LOG_PAGE_LINES });
    const fileSize = page?.fileSize ?? 0;
    // 日志文件被重建/截断：offset 已越过文件尾，继续按 offset 增量取会永远返回空，
    // 页面会一直显示旧内容。此处按"文件变小"判一次重建，回到末尾重新读。
    const rebuilt = !initial && fileSize > 0 && fileSize < logState.offset;
    logState.available = page?.available === true;
    logState.fileSize = fileSize;
    logState.lastModified = page?.lastModified ?? null;
    if (initial || rebuilt) {
      // reset/重建时无论有没有内容都要覆盖：否则"暂无日志文件"会和旧日志同屏出现
      logState.content = page?.content ?? "";
    } else if (page?.content) {
      logState.content += page.content;
    }
    logState.offset = page?.nextOffset ?? logState.offset;
    logState.loaded = true;
    logState.error = "";
    if (logState.content.length > LOG_MAX_CHARS) {
      logState.content = trimLogContent(logState.content);
    }
    await nextTick();
    // 用户向上翻看历史日志时不要被轮询强行拉回底部
    if (stickToBottom) scrollLogToBottom();
    else if (rebuilt) scrollLogToBottom();
  } catch {
    // 读取失败不能永远停在"加载中"：给出可重试的提示（自动刷新仍可恢复）
    logState.loaded = true;
    logState.error = logState.content ? "" : "日志加载失败，请点击刷新重试";
  } finally {
    logState.loading = false;
    if (pendingLogReset) {
      pendingLogReset = false;
      loadLogs(true);
    } else if (pendingLogTail) {
      pendingLogTail = false;
      loadLogs(false);
    }
  }
}

/**
 * 截断超长日志时只保留**完整行**：直接从中间切会留下半行，也会把代理对（emoji）切一半，
 * 渲染出替换字符。
 */
function trimLogContent(content) {
  let start = content.length - LOG_MAX_CHARS;
  // 先退到下一个换行之后，保证不切行中间
  const nextNewline = content.indexOf("\n", start);
  if (nextNewline > 0 && nextNewline < content.length - 1) {
    start = nextNewline + 1;
  }
  // 再避免切在代理对中间
  if (start > 0 && start < content.length && /[\uDC00-\uDFFF]/.test(content[start])) {
    start += 1;
  }
  return content.slice(start);
}

function isLogNearBottom() {
  const el = logViewer.value;
  if (!el) return true;
  return el.scrollHeight - el.scrollTop - el.clientHeight < 40;
}

function scrollLogToBottom() {
  const el = logViewer.value;
  if (el) el.scrollTop = el.scrollHeight;
}

function startLogPolling() {
  stopLogPolling();
  if (!logState.autoRefresh || FINAL_STATUSES.includes(status.value)) return;
  logTimer = setInterval(() => loadLogs(false), 2000);
}

function stopLogPolling() {
  if (logTimer) {
    clearInterval(logTimer);
    logTimer = null;
  }
}

function handleLogAutoRefresh(value) {
  if (value) {
    startLogPolling();
  } else {
    stopLogPolling();
  }
}

async function handleDownloadLog() {
  try {
    const blob = await downloadTaskLog(taskId);
    const url = URL.createObjectURL(blob);
    const link = document.createElement("a");
    link.href = url;
    link.download = `${taskId}.log`;
    link.click();
    URL.revokeObjectURL(url);
  } catch {
    message.error("日志下载失败");
  }
}

onMounted(async () => {
  await loadTask(true);
  if (!FINAL_STATUSES.includes(status.value)) startPolling();
  if (status.value === "SUCCEEDED") loadDiffReport();
  if (activeTab.value === "logs") {
    loadLogs(true);
    startLogPolling();
  }
});

onBeforeUnmount(() => {
  stopPolling();
  stopLogPolling();
});
</script>

<template>
  <div class="page-container">
    <n-spin :show="loading && !task">
      <template v-if="task">
        <!-- 头部 -->
        <div class="detail-header">
          <div class="header-left">
            <n-button quaternary circle class="back-btn" @click="router.push('/instances')">
              <template #icon>
                <n-icon><ArrowBackOutline /></n-icon>
              </template>
            </n-button>
            <div>
              <div class="title-row">
                <copyable-id class="task-id" :value="task.taskId" :length="26" />
                <StateTag :status="status" />
                <copyable-id v-if="task.serialNo" class="serial-no" :value="task.serialNo" :length="24" />
              </div>
              <div class="meta-row">
                <span class="meta-item">
                  <n-icon size="13"><TimeOutline /></n-icon>
                  提交于 {{ formatTime(task.submitTime) }}
                </span>
                <span v-if="task.definitionName" class="meta-item">
                  <n-icon size="13"><PlayCircleOutline /></n-icon>
                  <a class="meta-link" @click="router.push(`/definitions/${task.definitionId}`)">{{ task.definitionName }}</a>
                </span>
                <span v-else class="meta-item">
                  <n-icon size="13"><PulseOutline /></n-icon>
                  <span class="text-muted">外部直传</span>
                </span>
                <span v-if="task.executeNodeKey" class="meta-item">
                  <n-icon size="13"><ServerOutline /></n-icon>
                  {{ task.executeNodeKey }}
                </span>
                <span class="meta-item">
                  <n-icon size="13"><RefreshOutline /></n-icon>
                  重试 {{ task.retryCount ?? 0 }} 次
                </span>
              </div>
            </div>
          </div>
          <div class="header-actions">
            <n-button secondary size="small" :loading="loading" @click="loadTask()">
              <template #icon>
                <n-icon><RefreshOutline /></n-icon>
              </template>
              刷新
            </n-button>
            <n-popconfirm
              v-if="nextActions.includes('retry')"
              @positive-click="handleRetry"
              positive-text="确认重试"
              negative-text="取消"
            >
              <template #trigger>
                <n-button size="small" type="warning" secondary>重试</n-button>
              </template>
              确认重试任务 {{ taskId }} 吗？
            </n-popconfirm>
            <n-popconfirm
              v-if="nextActions.includes('cancel')"
              @positive-click="handleCancel"
              positive-text="确认取消"
              negative-text="取消"
            >
              <template #trigger>
                <n-button size="small" type="error" secondary>取消</n-button>
              </template>
              确认取消任务 {{ taskId }} 吗？
            </n-popconfirm>
          </div>
        </div>

        <!-- 失败原因：集群/本地执行失败时直接展示，避免用户只能去翻日志 -->
        <n-alert
          v-if="hasFailure"
          class="failure-alert"
          :type="status === 'RETRYABLE' ? 'warning' : 'error'"
          :show-icon="true"
          :bordered="false"
        >
          <template #header>
            <span>{{ failureTitle }}</span>
            <code v-if="failureCode" class="failure-code">{{ failureCode }}</code>
          </template>
          <div v-if="failureMessage" class="failure-message">{{ failureMessage }}</div>
        </n-alert>

        <!-- 运行中横幅 -->
        <div v-if="status === 'RUNNING' || status === 'PENDING' || status === 'CLAIMED'" class="running-banner">
          <n-icon size="16" color="var(--primary)"><RefreshOutline /></n-icon>
          <span>
            任务正在{{ status === 'PENDING' ? '等待调度' : '执行中' }}，页面每 2 秒自动刷新<template v-if="runningElapsed"> · 已运行 {{ runningElapsed }}</template>
          </span>
        </div>

        <!-- 指标区（执行结果摘要） -->
        <div v-if="hasDiffData" class="metrics-grid">
          <!-- 匹配度 -->
          <div class="metric-card metric-score">
            <div class="metric-label">匹配度</div>
            <div class="score-wrap">
              <MatchScoreGauge :score="diffReport.matchScore" :status="diffReport.status" :size="64" />
            </div>
          </div>

          <!-- 差异记录 -->
          <div class="metric-card">
            <div class="metric-label">差异记录</div>
            <div class="metric-value" :class="{ danger: diffPercent > 0 }">{{ formatNumber(diffReport.totalDifferenceCount ?? 0) }}</div>
            <div class="metric-extra">共 {{ formatNumber(diffReport.sampleSize ?? 0) }} 条样本{{ diffReport.sampleTruncated ? "（已截断）" : "" }}</div>
            <div v-if="diffPercent !== null" class="pct-track">
              <div class="pct-fill" :style="{ width: `${Math.min(100, diffPercent)}%`, background: diffPercent > 5 ? 'var(--danger)' : 'var(--warning)' }" />
            </div>
            <div class="metric-extra">差异占比 {{ formatRatio(diffPercent) }}</div>
          </div>

          <!-- 差异构成（无数据时隐藏） -->
          <div v-if="diffBreakdown.length" class="metric-card">
            <div class="metric-label">差异构成</div>
            <div class="breakdown">
              <div v-for="item in diffBreakdown" :key="item.label" class="breakdown-row">
                <span class="b-dot" :style="{ background: item.color }" />
                <span class="b-label">{{ item.label }}</span>
                <span class="b-value">{{ formatNumber(item.value) }}</span>
              </div>
            </div>
          </div>

          <!-- 常规指标 -->
          <div v-for="m in metrics" :key="m.label" class="metric-card">
            <div class="metric-label">{{ m.label }}</div>
            <div class="metric-value">{{ m.value }}</div>
            <div class="metric-extra">{{ m.extra }}</div>
          </div>
        </div>

        <!-- Tab 面板 -->
        <n-tabs type="line" animated :value="activeTab" @update:value="handleTabChange" class="detail-tabs">
          <n-tab-pane name="overview" tab="概览">
            <n-card :bordered="true" title="基本信息" class="panel-card">
              <KeyValueList :items="overviewItems" :columns="3" />
            </n-card>
            <n-card v-if="clusterInfo" :bordered="true" title="集群执行" class="panel-card mt-12">
              <KeyValueList :items="clusterItems" :columns="3" />
              <a
                v-if="trackingUrl"
                class="tracking-link"
                :href="trackingUrl"
                target="_blank"
                rel="noopener noreferrer"
              >
                打开集群应用页 →
              </a>
            </n-card>
          </n-tab-pane>

          <n-tab-pane name="diff" tab="差异明细" class="diff-pane">
            <n-spin :show="diffReportLoading || diffPage.loading" class="diff-spin">
              <template v-if="hasDiffData">
                <div class="diff-layout">
                  <div class="diff-legend">
                    <span v-for="op in FILTER_OPS" :key="op.value" class="legend-item">
                      <n-checkbox
                        size="small"
                        :checked="diffOps.includes(op.value)"
                        @update:checked="handleDiffOpsChange(toggleOp(op.value))"
                      >
                        {{ op.label }}
                      </n-checkbox>
                    </span>
                    <span class="legend-sep" />
                    <span class="legend-item"><i class="swatch swatch-old" />旧值</span>
                    <span class="legend-item"><i class="swatch swatch-new" />新值</span>
                    <span class="legend-sep" />
                    <span class="legend-item column-switch">
                      <n-switch v-model:value="showAllColumns" size="small" />
                      <span class="switch-label">{{ showAllColumns ? "显示全部列" : "只看差异列" }}</span>
                    </span>
                    <span v-if="diffOps.length === FILTER_OPS.length" class="legend-all">全部类型</span>
                  </div>
                  <div class="diff-table-area">
                    <ValueDiffTable v-if="diffPage.items.length" :samples="diffPage.items" :show-all-columns="showAllColumns" />
                    <EmptyState
                      v-if="diffPage.loaded && !diffPage.items.length"
                      :title="diffOps.length === 0 ? '未选择差异类型' : '未发现差异记录'"
                      :description="diffOps.length === 0 ? '请在左侧勾选至少一种差异类型' : '本次比对两表数据完全一致'"
                    />
                  </div>
                  <div v-if="diffPage.loaded && diffPage.items.length" class="diff-pager">
                    <n-pagination
                      :page="diffPage.page"
                      :page-size="diffPage.pageSize"
                      :item-count="diffPage.total"
                      :page-sizes="[10, 20, 50]"
                      show-size-picker
                      @update:page="handleDiffPageChange"
                      @update:page-size="handleDiffPageSizeChange"
                    />
                    <span class="text-muted diff-total">共 {{ formatNumber(diffPage.total) }} 条</span>
                  </div>
                </div>
              </template>
              <EmptyState
                v-else-if="diffNotReady"
                title="任务尚未完成"
                description="任务执行完成后即可查看差异报告"
              />
              <EmptyState
                v-else-if="diffReportError"
                title="差异报告加载失败"
                description="报告拉取失败（不是“没有差异”），可重试"
              >
                <n-button size="small" :loading="diffReportLoading" @click="loadDiffReport">
                  重试
                </n-button>
              </EmptyState>
              <EmptyState v-else title="暂无差异数据" description="该任务没有可用的差异报告" />
            </n-spin>
          </n-tab-pane>

          <n-tab-pane name="columns" tab="列分析">
            <n-spin :show="diffReportLoading">
              <template v-if="hasDiffData">
                <ColumnDiffTable v-if="(diffReport.columns || []).length" :columns="diffReport.columns" />
                <EmptyState
                  v-else
                  title="暂无列级统计"
                  description="差异样本中未包含变更列信息（仅 MISMATCH 行提供变更列）"
                />
              </template>
              <EmptyState
                v-else-if="diffNotReady"
                title="任务尚未完成"
                description="任务执行完成后即可查看列级分析"
              />
              <EmptyState v-else title="暂无列分析数据" description="该任务没有可用的列级统计" />
            </n-spin>
          </n-tab-pane>

          <n-tab-pane name="logs" tab="运行日志">
            <div class="run-log-panel">
              <div class="run-log-toolbar">
                <span class="text-muted">
                  {{ logState.available ? `共 ${formatNumber(logState.fileSize)} 字节` : "暂无日志文件" }}
                </span>
                <span v-if="logState.lastModified" class="text-muted">
                  更新于 {{ formatTime(logState.lastModified) }}
                </span>
                <span class="flex-1" />
                <span class="log-switch">
                  <n-switch
                    v-model:value="logState.autoRefresh"
                    size="small"
                    @update:value="handleLogAutoRefresh"
                  />
                  <span class="switch-label">自动刷新</span>
                </span>
                <n-button size="small" :loading="logState.loading" @click="loadLogs(true)">刷新</n-button>
                <n-button size="small" :disabled="!logState.available" @click="handleDownloadLog">下载</n-button>
              </div>
              <pre ref="logViewer" class="run-log">{{ logState.content || logState.error || (logState.loaded ? "暂无日志输出" : "加载中…") }}</pre>
            </div>
          </n-tab-pane>
        </n-tabs>
      </template>

      <n-card v-else-if="!loading && loadError" :bordered="true">
        <EmptyState title="加载失败" description="网络或服务异常，请稍后重试" action="重试" @action="loadTask(true)" />
      </n-card>

      <n-card v-else-if="!loading" :bordered="true">
        <EmptyState title="任务不存在" description="任务可能已被清理" action="返回任务列表" @action="router.push('/instances')" />
      </n-card>
    </n-spin>
  </div>
</template>

<style scoped lang="scss">
// 详情页内容随卡片自然生长，允许整页滚动
.page-container {
  overflow: auto;

  > * {
    flex-shrink: 0;
  }
}

// ===== 头部 =====
.detail-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding: 16px 20px;
  background: var(--card);
  border: 1px solid var(--border);
  border-radius: var(--radius-card);
}

// ===== 失败原因 =====
.failure-alert {
  margin-bottom: 16px;

  .failure-code {
    margin-left: 8px;
    padding: 1px 6px;
    border-radius: 4px;
    background: var(--bg-subtle);
    color: var(--text-secondary);
    font-family: "JetBrains Mono", ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace;
    font-size: 12px;
    font-weight: 500;
  }

  .failure-message {
    margin-top: 4px;
    max-height: 220px;
    overflow: auto;
    white-space: pre-wrap;
    word-break: break-word;
    font-family: "JetBrains Mono", ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace;
    font-size: 12px;
    line-height: 1.6;
    color: var(--text-secondary);
  }
}

.header-left {
  display: flex;
  align-items: center;
  gap: 12px;
  min-width: 0;
}

.back-btn {
  flex-shrink: 0;
}

.title-row {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}

.task-id {
  font-size: 16px;
  font-weight: 600;
  line-height: 24px;
}

.serial-no {
  font-size: 12px;
}

.meta-row {
  display: flex;
  align-items: center;
  gap: 16px;
  margin-top: 2px;
  flex-wrap: wrap;
}

.meta-item {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  font-size: 12px;
  color: var(--text-secondary);
}

.meta-link {
  color: var(--primary-text);
  cursor: pointer;

  &:hover {
    text-decoration: underline;
  }
}

.header-actions {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-shrink: 0;
}

// 窄屏：标题区与操作区分行，避免操作按钮把返回键挤出/盖住
@media (max-width: 768px) {
  .detail-header {
    flex-wrap: wrap;
    align-items: flex-start;
  }

  .header-actions {
    width: 100%;
    flex-wrap: wrap;
    justify-content: flex-end;
  }
}

// ===== 运行中横幅 =====
.running-banner {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 10px 16px;
  margin-top: 12px;
  font-size: 13px;
  color: var(--primary-text);
  background: var(--primary-bg);
  border-radius: 8px;
}

// ===== 指标网格 =====
.metrics-grid {
  display: grid;
  grid-template-columns: repeat(7, 1fr);
  gap: 10px;
  margin-top: 12px;
}

.metric-card {
  min-width: 0;
  padding: 10px 12px;
  background: var(--card);
  border: 1px solid var(--border);
  border-radius: var(--radius-card);
}

.metric-label {
  font-size: 11px;
  font-weight: 500;
  color: var(--text-secondary);
  margin-bottom: 4px;
}

.metric-value {
  font-family: "JetBrains Mono", ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace;
  font-variant-numeric: tabular-nums;
  font-size: 16px;
  font-weight: 600;
  line-height: 1.2;
  color: var(--text);

  &.danger {
    color: var(--danger);
  }
}

.metric-extra {
  margin-top: 2px;
  font-size: 10.5px;
  color: var(--text-muted);
}

.pct-track {
  height: 4px;
  margin-top: 6px;
  border-radius: 2px;
  background: var(--border);
  overflow: hidden;
}

.pct-fill {
  height: 100%;
  border-radius: 2px;
}

.metric-score {
  .score-wrap {
    display: flex;
    align-items: center;
    justify-content: center;
  }
}

.breakdown {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.breakdown-row {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 12px;
}

.b-dot {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  flex-shrink: 0;
}

.b-label {
  color: var(--text-secondary);
}

.b-value {
  margin-left: auto;
  font-weight: 600;
  color: var(--text);
  font-variant-numeric: tabular-nums;
}

// ===== Tab 面板 =====
.detail-tabs {
  margin-top: 12px;

  :deep(.n-tabs-nav) {
    padding: 0 4px;
  }
}

// 集群应用跟踪入口：与正文同色系，悬停下划线
.tracking-link {
  display: inline-block;
  margin-top: 12px;
  font-size: 13px;
  color: var(--primary-text);
  text-decoration: none;

  &:hover {
    text-decoration: underline;
  }
}

.diff-pane {
  height: calc(100vh - 320px);
  min-height: 380px;
}

// ===== 运行日志 =====
.run-log-panel {
  display: flex;
  flex-direction: column;
  gap: 8px;
  height: calc(100vh - 320px);
  min-height: 380px;
}

.run-log-toolbar {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-shrink: 0;
  font-size: 12px;

  .log-switch {
    display: inline-flex;
    align-items: center;
    gap: 6px;
  }

  .switch-label {
    font-size: 12px;
    color: var(--text-secondary);
  }
}

.run-log {
  flex: 1;
  min-height: 0;
  margin: 0;
  padding: 12px 14px;
  overflow: auto;
  background: var(--code-bg);
  border: 1px solid var(--code-border);
  border-radius: var(--radius-ctl);
  color: var(--code-text);
  font-family: "JetBrains Mono", ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace;
  font-size: 12px;
  line-height: 1.6;
  white-space: pre-wrap;
  word-break: break-all;
}

.diff-spin,
.diff-spin :deep(.n-spin-container),
.diff-spin :deep(.n-spin-content) {
  height: 100%;
}

.diff-layout {
  display: flex;
  flex-direction: column;
  height: 100%;
  // 高度由外层 .diff-pane 统一扣除（页头 + 统计卡 + tab），这里只占满剩余空间：
  // 表格内滚，分页控件始终留在视口内（否则整页被长表撑开，分页落到首屏之外）
}

// 勾选筛选栏：独立元素，固定不随表格刷新
.diff-legend {
  flex-shrink: 0;
  display: flex;
  align-items: center;
  gap: 14px;
  padding: 0 2px 10px;
  font-size: 12px;
  color: var(--text-secondary);

  .legend-item {
    display: inline-flex;
    align-items: center;
    gap: 5px;

    :deep(.n-checkbox) {
      font-size: 12px;

      .n-checkbox__label {
        font-size: 12px;
      }
    }
  }

  .column-switch {
    display: inline-flex;
    align-items: center;
    gap: 6px;

    .switch-label {
      font-size: 12px;
      color: var(--text-secondary);
    }
  }

  .legend-sep {
    width: 1px;
    height: 12px;
    background: var(--border);
  }

  .swatch {
    display: inline-block;
    width: 14px;
    height: 10px;
    border-radius: 3px;

    &.swatch-old { background: var(--diff-old-bg); border: 1px solid var(--diff-old-text); }
    &.swatch-new { background: var(--diff-new-bg); border: 1px solid var(--diff-new-text); }
  }

  .legend-all {
    margin-left: auto;
    font-size: 12px;
    color: var(--text-muted);
  }
}

// 表格区域：表格内部滚动（表头固定），外层不再产生滚动条
.diff-table-area {
  flex: 1;
  min-height: 0;
  overflow: hidden;
}

.diff-pager {
  flex-shrink: 0;
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 12px;
  padding: 4px 0 6px;
}

.diff-total {
  font-size: 12px;
  flex-shrink: 0;
}

// ===== 响应式 =====
@media (max-width: 1200px) {
  .metrics-grid {
    grid-template-columns: repeat(3, 1fr);
  }
}

@media (max-width: 768px) {
  .metrics-grid {
    grid-template-columns: repeat(2, 1fr);
  }
}
</style>
