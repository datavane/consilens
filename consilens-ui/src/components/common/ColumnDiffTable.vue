<script setup>
import { computed, h } from "vue";

const props = defineProps({
  // diff-report.columns（可能为空数组）
  columns: { type: Array, default: () => [] },
});

const isExact = computed(() =>
  props.columns.some((column) => column.aggregatedFromSamples === false)
);

// 后端 differencePercentage 为 0..100 语义；精确模式另有 differenceRate。
function formatPct(value, digits = 1) {
  if (value === null || value === undefined || Number.isNaN(Number(value))) return "-";
  return `${Number(value).toFixed(digits)}%`;
}

function rateOf(row) {
  return row.differenceRate ?? row.differencePercentage;
}

const columns = [
  { title: "列名", key: "name", minWidth: 160 },
  {
    title: "新增",
    key: "insertCount",
    width: 90,
    align: "right",
    render: (row) => h("span", { class: "num" }, formatCount(row.insertCount)),
  },
  {
    title: "变更",
    key: "updateCount",
    width: 90,
    align: "right",
    render: (row) => h("span", { class: "num" }, formatCount(row.updateCount)),
  },
  {
    title: "删除",
    key: "deleteCount",
    width: 90,
    align: "right",
    render: (row) => h("span", { class: "num" }, formatCount(row.deleteCount)),
  },
  {
    title: "差异合计",
    key: "differenceCount",
    width: 110,
    align: "right",
    render: (row) => h("span", { class: "num" }, formatCount(row.differenceCount)),
  },
  {
    title: "差异率",
    key: "differenceRate",
    minWidth: 200,
    render: (row) =>
      h("div", { class: "pct-cell" }, [
        h("div", { class: "pct-bar-wrap" }, [
          h("div", {
            class: "pct-bar",
            style: {
              width: `${Math.max(0, Math.min(100, rateOf(row) ?? 0))}%`,
              background: "var(--warning)",
            },
          }),
        ]),
        h("span", { class: "pct-text" }, formatPct(rateOf(row), 2)),
      ]),
  },
];

function formatCount(value) {
  if (value === null || value === undefined) return "-";
  return Number(value).toLocaleString("en-US");
}
</script>

<template>
  <div>
    <n-data-table
      :columns="columns"
      :data="props.columns"
      :max-height="420"
      size="small"
      :row-key="(row) => row.name"
    />
    <div class="col-hint text-muted">
      <template v-if="isExact">
        列级统计来自完整运行结果（insert / update / delete 精确计数）
      </template>
      <template v-else>
        列级统计由差异样本聚合而来（仅覆盖变更行），供快速定位高频率变更列
      </template>
    </div>
  </div>
</template>

<style scoped lang="scss">
.num {
  font-variant-numeric: tabular-nums;
}

.pct-cell {
  display: flex;
  align-items: center;
  gap: 8px;
}

.pct-bar-wrap {
  flex: 1;
  max-width: 160px;
  height: 6px;
  border-radius: 3px;
  background: var(--border);
  overflow: hidden;
}

.pct-bar {
  height: 100%;
  border-radius: 3px;
}

.pct-text {
  min-width: 48px;
  font-size: 12px;
  color: var(--text-secondary);
  font-variant-numeric: tabular-nums;
}

.col-hint {
  margin-top: 8px;
  font-size: 12px;
}
</style>
