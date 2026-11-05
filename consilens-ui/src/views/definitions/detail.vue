<script setup>
import { ref, computed, h, onMounted } from "vue";
import { useRoute, useRouter } from "vue-router";
import { useMessage, useDialog } from "naive-ui";
import { NButton, NIcon, NDataTable, NTag, NPopconfirm, NSpin } from "naive-ui";
import { ArrowBackOutline, PlayCircleOutline, PencilOutline, TrashOutline } from "@vicons/ionicons5";
import { getTaskDefinition, deleteTaskDefinition, runTaskDefinition, toggleTaskDefinition } from "@/api/modules";
import { formatTime, formatDuration } from "@/utils/format";
import StateTag from "@/components/common/StateTag.vue";
import EmptyState from "@/components/common/EmptyState.vue";
import CodeBlock from "@/components/common/CodeBlock.vue";
import TimeText from "@/components/common/TimeText.vue";

const route = useRoute();
const router = useRouter();
const message = useMessage();
const dialog = useDialog();

const definitionId = route.params.definitionId;
const loading = ref(true);
const definition = ref(null);
const detailTab = ref("config");
const running = ref(false);

// 404 才是"不存在"；500/断网要说清是加载失败，并给重试入口（否则用户会以为定义被删了）
const loadFailed = ref(false);

async function loadData() {
  loading.value = true;
  loadFailed.value = false;
  try {
    definition.value = await getTaskDefinition(definitionId);
  } catch (error) {
    definition.value = null;
    loadFailed.value = error?.status !== 404;
  } finally {
    loading.value = false;
  }
}

async function handleRun() {
  if (running.value) return;
  running.value = true;
  try {
    // 定义的超时配置存在 executionOptions.timeoutMs，运行时必须随 options 下发，
    // 否则保存的“超时时间”对集群执行不生效。
    const timeoutMs = definition.value?.config?.executionOptions?.timeoutMs;
    const accepted = await runTaskDefinition(definitionId, {
      options: timeoutMs ? { timeoutMs } : undefined,
    });
    message.success(`已提交运行：${accepted.taskId}`);
    router.push(`/instances/${accepted.taskId}`);
  } catch (error) {
    message.error(error?.response?.data?.error || "运行失败");
  } finally {
    running.value = false;
  }
}

async function handleToggle() {
  const next = !definition.value.enabled;
  try {
    await toggleTaskDefinition(definitionId, next);
    definition.value.enabled = next;
    message.success(next ? "已启用" : "已停用");
  } catch (error) {
    message.error(error?.response?.data?.error || "操作失败");
  }
}

function handleDelete() {
  dialog.warning({
    title: "删除任务定义",
    content: `确认删除定义「${definition.value?.name}」吗？历史运行记录将保留。`,
    positiveText: "删除",
    negativeText: "取消",
    onPositiveClick: async () => {
      try {
        await deleteTaskDefinition(definitionId);
        message.success("已删除");
        router.push("/definitions");
      } catch (error) {
        message.error(error?.response?.data?.error || "删除失败");
      }
    },
  });
}

const configText = computed(() => {
  const config = definition.value?.config;
  return config ? JSON.stringify(config, null, 2) : "";
});

const summaryItems = computed(() => {
  const config = definition.value?.config;
  if (!config) return [];
  const source = config.source || {};
  const target = config.target || {};
  const comparison = config.comparison || {};
  const fields = comparison.fieldMappings?.length
    ? comparison.fieldMappings.map((m) => `${m.source}→${m.target}`).join(", ")
    : (comparison.fields || []).join(", ");
  return [
    { label: "目标描述", value: config.goal || "-" },
    { label: "源端", value: `${source.type || "?"} · ${source.table || "(查询)"}` },
    { label: "目标端", value: `${target.type || "?"} · ${target.table || "(查询)"}` },
    { label: "主键", value: (config.keys || []).join(", ") || "-" },
    { label: "比较字段", value: fields || "全部非主键列" },
    { label: "排除字段", value: (comparison.ignoreColumns || []).join(", ") || "-" },
  ];
});

const recentColumns = [
  {
    title: "实例 ID",
    key: "instanceId",
    render: (row) =>
      h("a", { class: "app-name-link mono", onClick: () => router.push(`/instances/${row.instanceId}`) }, row.instanceId),
  },
  { title: "序号", key: "serialNo", ellipsis: { tooltip: true }, render: (row) => h("span", { class: "mono text-secondary" }, row.serialNo) },
  {
    title: "状态",
    key: "status",
    width: 110,
    render: (row) => h(StateTag, { status: row.status }),
  },
  {
    title: "提交时间",
    key: "submitTime",
    width: 170,
    render: (row) => h(TimeText, { value: row.submitTime }),
  },
  {
    title: "耗时",
    key: "duration",
    width: 100,
    render: (row) => h("span", { class: "num text-secondary" }, formatDuration(row.submitTime, row.endTime)),
  },
];

onMounted(loadData);
</script>

<template>
  <div class="page-container">
    <n-spin :show="loading && !definition">
      <template v-if="definition">
        <div class="detail-header">
          <div class="header-left">
            <n-button quaternary circle class="back-btn" @click="router.push('/definitions')">
              <template #icon>
                <n-icon><ArrowBackOutline /></n-icon>
              </template>
            </n-button>
            <div>
              <div class="title-row">
                <span class="def-name">{{ definition.name }}</span>
                <NTag :type="definition.enabled ? 'success' : 'default'" size="small" :bordered="false">
                  {{ definition.enabled ? "已启用" : "已停用" }}
                </NTag>
              </div>
              <div class="meta-row">
                <span class="meta-item">创建于 {{ formatTime(definition.createdAt) }}</span>
                <span v-if="definition.lastRunAt" class="meta-item">
                  上次运行 <TimeText :value="definition.lastRunAt" />
                </span>
              </div>
            </div>
          </div>
          <div class="header-actions">
            <n-button size="small" type="primary" :disabled="!definition.enabled" :loading="running" @click="handleRun">
              <template #icon>
                <n-icon><PlayCircleOutline /></n-icon>
              </template>
              立即运行
            </n-button>
            <n-button size="small" secondary @click="handleToggle">
              {{ definition.enabled ? "停用" : "启用" }}
            </n-button>
            <n-button size="small" secondary @click="router.push(`/definitions/new?editId=${definition.id}`)">
              <template #icon>
                <n-icon><PencilOutline /></n-icon>
              </template>
              编辑
            </n-button>
            <n-button size="small" type="error" secondary @click="handleDelete">
              <template #icon>
                <n-icon><TrashOutline /></n-icon>
              </template>
              删除
            </n-button>
          </div>
        </div>

        <n-card :bordered="true" class="panel-card">
          <n-tabs type="line" animated :value="detailTab" @update:value="(v) => (detailTab = v)">
            <n-tab-pane name="config" tab="运行配置">
            <div class="summary-list">
              <div v-for="item in summaryItems" :key="item.label" class="summary-row">
                <span class="summary-label">{{ item.label }}</span>
                <span class="summary-value">{{ item.value }}</span>
              </div>
            </div>
            <CodeBlock :content="configText" label="运行配置 JSON" :max-height="360" />
            </n-tab-pane>
            <n-tab-pane name="recent" tab="最近运行">
            <n-data-table
              v-if="(definition.recentInstances || []).length"
              :columns="recentColumns"
              :data="definition.recentInstances"
              size="small"
              :bordered="false"
              :row-key="(row) => row.instanceId"
            />
            <EmptyState
              v-else
              title="暂无运行记录"
              description="点击「立即运行」创建第一条运行实例"
            />
            </n-tab-pane>
          </n-tabs>
        </n-card>
      </template>

      <n-card v-else-if="!loading && loadFailed" :bordered="true">
        <EmptyState title="定义加载失败" description="请求出错（不是“定义不存在”），可重试">
          <n-button size="small" :loading="loading" @click="loadData">重试</n-button>
        </EmptyState>
      </n-card>
      <n-card v-else-if="!loading" :bordered="true">
        <EmptyState title="定义不存在" description="定义可能已被删除" action="返回定义列表" @action="router.push('/definitions')" />
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
}

.def-name {
  font-size: 16px;
  font-weight: 600;
  line-height: 24px;
  color: var(--text);
}

.meta-row {
  display: flex;
  align-items: center;
  gap: 16px;
  margin-top: 2px;
  flex-wrap: wrap;
}

.meta-item {
  font-size: 12px;
  color: var(--text-muted);
}

.header-actions {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-shrink: 0;
}

.panel-card {
  margin-top: 12px;
}

.summary-list {
  border: 1px solid var(--border);
  border-radius: 8px;
  overflow: hidden;
  margin-bottom: 12px;
}

.summary-row {
  display: flex;
  align-items: baseline;
  padding: 8px 14px;
  font-size: 13px;

  &:nth-child(odd) {
    background: var(--bg-subtle);
  }

  .summary-label {
    flex: 0 0 90px;
    color: var(--text-muted);
  }

  .summary-value {
    flex: 1;
    color: var(--text);
    word-break: break-all;
  }
}

// 窄屏：标题区与操作区分行，避免 4 个操作按钮把返回键挤出/盖住
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
</style>
