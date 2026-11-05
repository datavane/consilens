<script setup>
import { ref, reactive, onMounted, h } from "vue";
import { useRouter } from "vue-router";
import { useMessage } from "naive-ui";
import { RefreshOutline } from "@vicons/ionicons5";
import { listArtifacts } from "@/api/modules";
import { ARTIFACT_TYPE_OPTIONS } from "@/common/statusMapping";
import { useListQuerySync } from "@/composables/useListQuerySync";
import { formatBytes, buildPageParams } from "@/utils/format";
import StateTag from "@/components/common/StateTag.vue";
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
  pageSize: 20,
  itemCount: 0,
  pageSizes: [10, 20, 50, 100],
  showSizePicker: true,
  prefix: ({ itemCount }) => `共 ${itemCount} 条`,
});

// 筛选与 URL 双向同步：初始从 query 恢复，每次加载写回
const filters = reactive({
  artifactType: null,
  keyword: "",
  startTime: null,
  endTime: null,
});

const { filtersActive, syncToUrl } = useListQuerySync(filters, pagination, {
  artifactType: { queryKey: "type", parse: (v) => v || null },
  keyword: {},
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
    const result = await listArtifacts(
      buildPageParams(pagination.page, pagination.pageSize, {
        artifactType: filters.artifactType ? [filters.artifactType] : undefined,
        keyword: filters.keyword,
        startTime: filters.startTime,
        endTime: filters.endTime,
      })
    );
    if (seq !== loadSeq) return;
    data.total = result.total;
    data.items = result.items || [];
    pagination.itemCount = result.total;
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
  filters.artifactType = null;
  filters.keyword = "";
  filters.startTime = null;
  filters.endTime = null;
  handleSearch();
}

const columns = [
  {
    title: "Artifact ID",
    key: "id",
    ellipsis: { tooltip: true },
    render: (row) =>
      h(
        "a",
        {
          class: "app-name-link mono",
          onClick: () => router.push(`/artifacts/${row.artifactId}`),
        },
        row.artifactId
      ),
  },
  {
    title: "类型",
    key: "artifactType",
    width: 110,
    render: (row) => h(StateTag, { status: row.artifactType }),
  },
  { title: "格式", key: "format", width: 80, render: (row) => h("span", { class: "text-secondary" }, row.format || "-") },
  {
    title: "大小",
    key: "sizeBytes",
    width: 100,
    align: "right",
    render: (row) => h("span", { class: "num" }, formatBytes(row.sizeBytes)),
  },
  {
    title: "元数据",
    key: "metadata",
    ellipsis: { tooltip: true },
    render: (row) =>
      row.metadata && Object.keys(row.metadata).length
        ? h(
            "span",
            { class: "text-secondary" },
            Object.entries(row.metadata)
              .map(([k, v]) => `${k}: ${typeof v === "object" ? JSON.stringify(v) : v}`)
              .join(" · ")
          )
        : h("span", { class: "text-muted" }, "-"),
  },
  {
    title: "创建时间",
    key: "createdAt",
    width: 170,
    render: (row) => h(TimeText, { value: row.createdAt }),
  },
  {
    title: "操作",
    key: "actions",
    width: 150,
    render: (row) =>
      h("div", { class: "row-actions" }, [
        h(
          "a",
          { class: "row-action", onClick: () => router.push(`/artifacts/${row.artifactId}`) },
          "查看"
        ),
        h("a", { class: "row-action", onClick: () => copyId(row.artifactId) }, "复制 ID"),
      ]),
  },
];

function copyId(id) {
  navigator.clipboard.writeText(id);
  message.success("已复制 artifactId");
}

onMounted(loadData);
</script>

<template>
  <div class="page-container">
    <div class="page-header">
      <div>
        <div class="page-title">Artifact 中心</div>
        <div class="page-desc">所有任务产生的配置、校验结果、诊断报告与运行结果</div>
      </div>
    </div>

    <n-card size="small" :bordered="true" class="mt-12 mb-12">
      <div class="filter-bar">
        <n-select
          v-model:value="filters.artifactType"
          :options="ARTIFACT_TYPE_OPTIONS"
          placeholder="全部类型"
          clearable
          size="small"
          class="filter-item"
        />
        <n-input
          v-model:value="filters.keyword"
          placeholder="搜索 artifactId"
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
        :title="loadError ? '加载失败' : filtersActive ? '没有匹配的 Artifact' : '暂无 Artifact'"
        :description="loadError ? '网络或服务异常，请重试' : filtersActive ? '尝试调整或清除筛选条件' : '任务运行产生的配置、结果与报告都会存放在这里'"
        :action="loadError ? '重试' : filtersActive ? '清除筛选' : ''"
        @action="loadError ? loadData() : handleReset()"
      />
      <n-data-table
        v-else
        class="main-table"
        :columns="columns"
        :data="data.items"
        :loading="loading"
        :row-key="(row) => row.artifactId"
        flex-height
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
