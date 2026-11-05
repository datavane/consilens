<script setup>
import { ref, reactive, onMounted, h } from "vue";
import { useRouter } from "vue-router";
import { useMessage } from "naive-ui";
import { NPopconfirm, NTag } from "naive-ui";
import { RefreshOutline } from "@vicons/ionicons5";
import { listTaskInstances, retryTask, cancelTask } from "@/api/modules";
import { TASK_STATUS_OPTIONS } from "@/common/statusMapping";
import { useListQuerySync } from "@/composables/useListQuerySync";
import { formatDuration, buildPageParams } from "@/utils/format";
import StateTag from "@/components/common/StateTag.vue";
import DiffSummaryBadge from "@/components/common/DiffSummaryBadge.vue";
import EmptyState from "@/components/common/EmptyState.vue";
import SkeletonTable from "@/components/common/SkeletonTable.vue";
import TimeText from "@/components/common/TimeText.vue";

const router = useRouter();
const message = useMessage();

const loading = ref(false);
const loadError = ref(false);
const data = reactive({ total: 0, items: [] });
const pagination = reactive({
  page: 1,
  pageSize: 10,
  itemCount: 0,
  pageSizes: [10, 20, 50],
  showSizePicker: true,
  prefix: ({ itemCount }) => `共 ${itemCount} 条`,
});

// 筛选状态与 URL 双向同步：初始从 query 恢复，每次加载写回（可分享 / 刷新保留）
const filters = reactive({
  status: [],
  keyword: "",
  executeNodeKey: "",
  startTime: null,
  endTime: null,
});

const { filtersActive, syncToUrl } = useListQuerySync(filters, pagination, {
  status: { parse: (v) => (Array.isArray(v) ? v : v ? [v] : []) },
  keyword: {},
  executeNodeKey: { queryKey: "node" },
  startTime: { parse: (v) => Number(v) || null, serialize: String },
  endTime: { parse: (v) => Number(v) || null, serialize: String },
});

let loadSeq = 0;

async function loadData() {
  const seq = ++loadSeq;
  loading.value = true;
  loadError.value = false;
  syncToUrl();
  try {
    const params = buildPageParams(pagination.page, pagination.pageSize, {
      status: filters.status.length ? filters.status : undefined,
      keyword: filters.keyword,
      executeNodeKey: filters.executeNodeKey,
      startTime: filters.startTime,
      endTime: filters.endTime,
      includeDiffSummary: true,
    });
    const result = await listTaskInstances(params);
    if (seq !== loadSeq) return;
    data.total = result.total;
    data.items = result.items || [];
    pagination.itemCount = result.total;
    // 页码越界（URL 手改、末页删光）时回退到最后一页：否则会停在 items=[] 的空态，
    // 分页条随表格一起消失，用户连"上一页"都点不到。
    const maxPage = Math.max(1, Math.ceil((result.total || 0) / pagination.pageSize));
    if (pagination.page > maxPage) {
      pagination.page = maxPage;
      loadData();
      return;
    }

  } catch {
    if (seq !== loadSeq) return;
    loadError.value = true;
    data.items = [];
    pagination.itemCount = 0;
  } finally {
    if (seq === loadSeq) {
      loading.value = false;
    }
  }
}

function handleSearch() {
  pagination.page = 1;
  loadData();
}

function handleReset() {
  filters.status = [];
  filters.keyword = "";
  filters.executeNodeKey = "";
  filters.startTime = null;
  filters.endTime = null;
  handleSearch();
}

const columns = [
  {
    title: "实例 ID",
    key: "id",
    width: 220,
    fixed: "left",
    ellipsis: { tooltip: true },
    render: (row) =>
      h(
        "a",
        {
          class: "app-name-link mono",
          style: "white-space: nowrap; display: inline-block;",
          onClick: () => router.push(`/instances/${row.taskId || row.id}`),
        },
        row.taskId || row.id
      ),
  },
  { title: "序号", key: "serialNo", width: 160, ellipsis: { tooltip: true }, render: (row) => h("span", { class: "mono text-secondary" }, row.serialNo) },
  {
    title: "定义",
    key: "definition",
    width: 130,
    ellipsis: { tooltip: true },
    render: (row) =>
      row.definitionName
        ? h("a", { class: "app-name-link", onClick: () => router.push(`/definitions/${row.definitionId}`) }, row.definitionName)
        : row.definitionId
          ? h("span", { class: "text-muted" }, "已删除的定义")
          : h("span", { class: "text-muted" }, "外部直传"),
  },
  {
    title: "类型",
    key: "taskType",
    width: 96,
    render: (row) => h(NTag, { size: "small", bordered: false }, row.taskType),
  },
  {
    title: "状态",
    key: "status",
    width: 100,
    render: (row) => h(StateTag, { status: row.status }),
  },
  {
    title: "差异摘要",
    key: "diffSummary",
    width: 160,
    render: (row) => {
      if (!row.diffSummary) {
        return h("span", { class: "text-muted" }, "-");
      }
      return h(
        "a",
        {
          class: "diff-link",
          title: "查看差异报告",
          onClick: () => router.push({ path: `/instances/${row.id || row.taskId}`, query: { tab: "diff" } }),
        },
        h(DiffSummaryBadge, { summary: row.diffSummary })
      );
    },
  },
  { title: "执行节点", key: "executeNodeKey", width: 130, ellipsis: { tooltip: true }, render: (row) => row.executeNodeKey || "-" },
  { title: "重试", key: "retryCount", width: 60, align: "right", render: (row) => h("span", { class: "num" }, String(row.retryCount ?? 0)) },
  { title: "提交时间", key: "submitTime", width: 150, render: (row) => h(TimeText, { value: row.submitTime }) },
  {
    title: "耗时",
    key: "duration",
    width: 100,
    render: (row) => h("span", { class: "num text-secondary" }, formatDuration(row.startTime, row.endTime)),
  },
  {
    title: "操作",
    key: "actions",
    width: 130,
    fixed: "right",
    render: (row) => renderActions(row),
  },
];

function renderActions(row) {
  const actions = [
    h(
      "a",
      { class: "row-action", onClick: () => router.push(`/instances/${row.id || row.taskId}`) },
      "详情"
    ),
  ];
  if ((row.availableNextActions || []).includes("retry")) {
    actions.push(
      h(
        NPopconfirm,
        { onPositiveClick: () => handleRetry(row), positiveText: "确认重试", negativeText: "取消" },
        {
          trigger: () => h("a", { class: "row-action" }, "重试"),
          default: () => `确认重试任务 ${row.taskId} 吗？`,
        }
      )
    );
  }
  if ((row.availableNextActions || []).includes("cancel")) {
    actions.push(
      h(
        NPopconfirm,
        { onPositiveClick: () => handleCancel(row), positiveText: "确认取消", negativeText: "取消" },
        {
          trigger: () => h("a", { class: "row-action-danger" }, "取消"),
          default: () => `确认取消任务 ${row.taskId} 吗？`,
        }
      )
    );
  }
  return h("div", { class: "row-actions" }, { default: () => actions });
}

async function handleRetry(row) {
  await retryTask(row.taskId);
  message.success("已发起重试");
  loadData();
}

async function handleCancel(row) {
  await cancelTask(row.taskId);
  message.success("已发起取消");
  loadData();
}

onMounted(loadData);
</script>

<template>
  <div class="page-container">
    <div class="page-header">
      <div>
        <div class="page-title">运行实例</div>
        <div class="page-desc">任务定义的每一次执行记录，支持外部直传运行</div>
      </div>
    </div>

    <n-card size="small" :bordered="true" class="mt-12 mb-12">
      <div class="filter-bar">
        <n-select
          v-model:value="filters.status"
          :options="TASK_STATUS_OPTIONS"
          placeholder="全部状态"
          multiple
          clearable
          collapse-tags
          size="small"
          class="filter-item"
        />
        <n-input
          v-model:value="filters.keyword"
          placeholder="任务 ID / 序号"
          clearable
          size="small"
          class="filter-item"
          @keyup.enter="handleSearch"
        />
        <n-input
          v-model:value="filters.executeNodeKey"
          placeholder="执行节点"
          clearable
          size="small"
          class="filter-item"
          @keyup.enter="handleSearch"
        />
        <n-date-picker
          v-model:value="filters.startTime"
          type="datetime"
          clearable
          size="small"
          class="filter-item"
          placeholder="开始时间"
        />
        <n-date-picker
          v-model:value="filters.endTime"
          type="datetime"
          clearable
          size="small"
          class="filter-item"
          placeholder="结束时间"
        />
        <n-button size="small" type="primary" :loading="loading" @click="handleSearch">
          查询
        </n-button>
        <n-button size="small" @click="handleReset">重置</n-button>
        <n-button size="small" quaternary circle title="刷新" :loading="loading" @click="loadData">
          <template #icon>
            <n-icon><RefreshOutline /></n-icon>
          </template>
        </n-button>
      </div>
    </n-card>

    <n-card size="small" :bordered="true" class="table-card">
      <SkeletonTable v-if="loading && !data.items.length" :rows="8" />
      <EmptyState
        v-else-if="!loading && !data.items.length"
        class="flex-1"
        :title="loadError ? '加载失败' : filtersActive ? '没有匹配的运行实例' : '暂无运行实例'"
        :description="loadError ? '网络或服务异常，请重试' : filtersActive ? '尝试调整或清除筛选条件' : '任务提交后将在此展示执行记录'"
        :action="loadError ? '重试' : filtersActive ? '清除筛选' : ''"
        @action="loadError ? loadData() : handleReset()"
      />
      <n-data-table
        v-else
        class="main-table"
        :columns="columns"
        :data="data.items"
        :loading="loading"
        :row-key="(row) => row.taskId"
        flex-height
        :scroll-x="1540"
        :bordered="false"
        size="small"
        :pagination="pagination"
        remote
        @update:page="(page) => { pagination.page = page; loadData(); }"
        @update:page-size="(size) => { pagination.pageSize = size; pagination.page = 1; loadData(); }"
      />
    </n-card>
  </div>
</template>

<style scoped lang="scss">
// 列表横向滚动条始终可见，避免字段被隐藏的错觉
:deep(.n-data-table .n-scrollbar-rail--horizontal) {
  display: block !important;
  opacity: 1 !important;
}

:deep(.n-data-table .n-scrollbar-rail--horizontal .n-scrollbar-rail__scrollbar) {
  opacity: 1 !important;
}
</style>
