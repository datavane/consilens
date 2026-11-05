<script setup>
import { computed, h, ref } from "vue";
import { NButton, NDrawer, NDrawerContent, NDataTable } from "naive-ui";
import StateTag from "./StateTag.vue";
import { DIFF_OPERATION_LABELS } from "@/common/statusMapping";
import { parsePrimaryKey } from "@/utils/format";

// 差异明细展示（参考 DataGrip / Redgate Data Compare 等对比工具的配色习惯）：
// - 整行按操作类型着色：更新=琥珀、源缺失=绿、目标缺失=红（极浅背景）
// - 每个字段一列（源端/目标双列），变化字段旧值红色、新值绿色，未变化字段原样展示
// - 「详情」抽屉逐字段对比，变化的行红底、源红目标绿
const props = defineProps({
  // [{operation, primaryKey, metadata, changedColumns, columnNames, sourceValues, targetValues}]
  samples: { type: Array, default: () => [] },
  // 列显示开关（由父页面图例行的开关控制）：默认只显示不一致行的差异列
  showAllColumns: { type: Boolean, default: false },
});

// NULL 与空串必须可区分：后端把二者判为不同值（`compareValuesInternal` 里 null 与 "" 不等），
// 前端若都渲染成 ""，用户会看到"两侧都是 ∅ 却标红"，或者反过来以为没差异。
const NULL_TOKEN = { __null: true };

function formatValue(value) {
  if (value === null || value === undefined) return NULL_TOKEN;
  if (typeof value === "object") return JSON.stringify(value);
  return String(value);
}

function sameValue(left, right) {
  const a = formatValue(left);
  const b = formatValue(right);
  if (a === NULL_TOKEN || b === NULL_TOKEN) return a === b;
  return a === b;
}

function valueText(formatted) {
  if (formatted === NULL_TOKEN) return "NULL";
  return formatted === "" ? "∅" : formatted;
}

function opKey(sample) {
  return (sample.operation || "").toLowerCase();
}

function isMismatch(sample) {
  return opKey(sample) === "mismatch";
}

// mismatch 行的变化字段下标：优先 metadata.changedColumnIndices，
// 其次 changedColumns1/2（列名），最后逐值比较兜底
function mismatchChangedIndices(sample) {
  const names = sample.columnNames || [];
  const source = sample.sourceValues || [];
  const target = sample.targetValues || [];
  const metadata = sample.metadata || {};

  if (Array.isArray(metadata.changedColumnIndices) && metadata.changedColumnIndices.length) {
    return metadata.changedColumnIndices.filter((i) => i >= 0 && i < names.length);
  }

  const changedNames = new Set();
  for (const key of ["changedColumns1", "changedColumns2"]) {
    if (Array.isArray(metadata[key])) {
      for (const name of metadata[key]) changedNames.add(name);
    }
  }
  if (changedNames.size) {
    const indices = [];
    names.forEach((name, i) => {
      if (changedNames.has(name)) indices.push(i);
    });
    if (indices.length) return indices;
  }

  const indices = [];
  const max = Math.max(names.length, source.length, target.length);
  for (let i = 0; i < max; i++) {
    if (!sameValue(source[i], target[i])) indices.push(i);
  }
  return indices.filter((i) => i >= 0 && i < names.length);
}

// 该行展示用的字段对比：
// mismatch 行默认只输出差异列（“显示全部列”开关打开时输出全部，差异列标 diff）；
// 增删行输出全部字段（单侧有值即为差异语义）。
function fieldPairs(sample) {
  const names = sample.columnNames || [];
  const source = sample.sourceValues || [];
  const target = sample.targetValues || [];
  const changed = isMismatch(sample) ? new Set(mismatchChangedIndices(sample)) : null;
  if (changed !== null && !props.showAllColumns) {
    return names
      .map((name, i) => ({
        name,
        source: formatValue(source[i]),
        target: formatValue(target[i]),
        diff: changed.has(i),
      }))
      .filter((pair) => pair.diff);
  }
  return names.map((name, i) => ({
    name,
    source: formatValue(source[i]),
    target: formatValue(target[i]),
    diff: changed !== null ? changed.has(i) : false,
  }));
}

// 详情抽屉：全部字段逐项对比
function allFieldPairs(sample) {
  const names = sample.columnNames || [];
  const source = sample.sourceValues || [];
  const target = sample.targetValues || [];
  return names.map((name, i) => ({
    name,
    source: formatValue(source[i]),
    target: formatValue(target[i]),
    diff: !sameValue(source[i], target[i]),
  }));
}

const rows = computed(() =>
  props.samples
    .filter((sample) => opKey(sample) !== "unchanged")
    .map((sample, index) => ({
      key: index,
      sample,
      operationKey: opKey(sample),
      pairs: fieldPairs(sample),
    }))
);

// 所有行出现过的字段（并集，作为表格列；含未变化字段）
const fieldNames = computed(() => {
  const names = [];
  for (const row of rows.value) {
    for (const pair of row.pairs) {
      if (!names.includes(pair.name)) names.push(pair.name);
    }
  }
  return names;
});

function pairOf(row, name) {
  return row.pairs.find((p) => p.name === name) || null;
}

// 单元格渲染：mismatch 旧值红 / 新值绿；增删行单边着色；空值显示 ∅
function renderCell(row, name, side) {
  const pair = pairOf(row, name);
  if (!pair) return h("span", { class: "cell-muted" }, "-");
  const value = valueText(side === "source" ? pair.source : pair.target);
  const isNull = value === "NULL";
  const op = row.operationKey;

  if (op === "mismatch") {
    if (!pair.diff) {
      return h("span", { class: isNull ? "cell-plain cell-null" : "cell-plain" }, value);
    }
    return h(
      "span",
      { class: [side === "source" ? "cell-old" : "cell-new", isNull ? "cell-null" : ""] },
      value
    );
  }
  // 新增行展示目标值、删除行展示源值
  const hasValue = op === "source_missing" ? side === "target" : side === "source";
  if (!hasValue) return h("span", { class: "cell-muted" }, "—");
  return h(
    "span",
    { class: [op === "source_missing" ? "cell-new" : "cell-old", isNull ? "cell-null" : ""] },
    value
  );
}

const columns = computed(() => {
  const cols = [
    {
      title: "类型",
      key: "operation",
      width: 96,
      fixed: "left",
      render: (row) => h(StateTag, { status: row.sample.operation }),
    },
    {
      title: "主键",
      key: "primaryKey",
      minWidth: 150,
      fixed: "left",
      render: (row) => h("span", { class: "pk-text" }, parsePrimaryKey(row.sample.primaryKey)),
    },
  ];
  for (const name of fieldNames.value) {
    cols.push({
      title: name,
      key: `f_${name}`,
      align: "center",
      className: "field-header",
      children: [
        {
          title: "源端",
          key: `src_${name}`,
          width: 180,
          className: "sub-header",
          ellipsis: { tooltip: true },
          render: (row) => renderCell(row, name, "source"),
        },
        {
          title: "目标",
          key: `tgt_${name}`,
          width: 180,
          className: "sub-header",
          ellipsis: { tooltip: true },
          render: (row) => renderCell(row, name, "target"),
        },
      ],
    });
  }
  cols.push({
    title: "操作",
    key: "actions",
    width: 84,
    fixed: "right",
    render: (row) =>
      h(
        NButton,
        { size: "small", text: true, type: "primary", onClick: () => openDetail(row.sample) },
        { default: () => "详情" }
      ),
  });
  return cols;
});

const scrollX = computed(() => 96 + 150 + fieldNames.value.length * 360 + 84);

function rowClass(row) {
  if (row.operationKey === "source_missing") return "row-added";
  if (row.operationKey === "target_missing") return "row-removed";
  return "row-updated";
}

// ===== 差异详情抽屉 =====
const drawer = ref({ show: false, rows: [], title: "" });

function openDetail(sample) {
  const pk = parsePrimaryKey(sample.primaryKey);
  const opText = DIFF_OPERATION_LABELS[opKey(sample)]?.label || sample.operation;
  drawer.value = {
    show: true,
    rows: allFieldPairs(sample),
    title: `${opText} · 主键 ${pk}`,
  };
}

const detailColumns = [
  { title: "字段", key: "name", width: 200, fixed: "left", render: (row) => h("span", { class: "detail-name" }, row.name) },
  {
    title: "源端",
    key: "source",
    minWidth: 240,
    render: (row) => h("span", { class: row.diff ? "cell-old" : "" }, row.source || "∅"),
  },
  {
    title: "目标",
    key: "target",
    minWidth: 240,
    render: (row) => h("span", { class: row.diff ? "cell-new" : "" }, row.target || "∅"),
  },
];

function detailRowClass(row) {
  return row.diff ? "diff-cell-row" : "";
}
</script>

<template>
  <div class="value-diff-table">
    <n-data-table
      :columns="columns"
      :data="rows"
      :scroll-x="scrollX"
      flex-height
      :row-class-name="rowClass"
      size="small"
      :row-key="(row) => row.key"
      :bordered="false"
    />
    <n-drawer v-model:show="drawer.show" :width="760">
      <n-drawer-content :title="drawer.title" closable>
        <n-data-table
          :columns="detailColumns"
          :data="drawer.rows"
          :row-class-name="detailRowClass"
          size="small"
          :bordered="false"
          :max-height="560"
          :row-key="(row) => row.name"
        />
      </n-drawer-content>
    </n-drawer>
  </div>
</template>

<style scoped lang="scss">
.value-diff-table {
  height: 100%;

  // 表格根占满容器高度，flex-height 模式下 body 内部滚动、表头固定
  :deep(.n-data-table) {
    height: 100%;
  }

  // 隐藏垂直滚动条（滚轮滚动即可）；横向滚动条保留并常显，便于左右滑动查看字段
  :deep(.n-scrollbar-rail--vertical) {
    display: none !important;
  }

  :deep(.n-scrollbar-rail--horizontal) {
    display: block !important;
    opacity: 1 !important;
  }

  :deep(.n-scrollbar-rail--horizontal .n-scrollbar-rail__scrollbar) {
    opacity: 1 !important;
  }

  // ===== 单元格值 =====
  :deep(.cell-plain) {
    color: var(--text);
  }

  :deep(.cell-muted) {
    color: var(--text-muted);
  }

  // NULL 与空串在视觉上也要能分开：NULL 用斜体 + 弱色，空串保持 ∅
  :deep(.cell-null) {
    font-style: italic;
    color: var(--text-muted);
  }

  :deep(.cell-old),
  :deep(.cell-new) {
    display: inline-block;
    max-width: 100%;
    padding: 1px 8px;
    border-radius: 4px;
    font-weight: 600;
    font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
    font-size: 12px;
    line-height: 20px;
    word-break: break-all;
    white-space: normal;
  }

  :deep(.cell-old) {
    color: var(--diff-old-text);
    background: var(--diff-old-bg);
  }

  :deep(.cell-new) {
    color: var(--diff-new-text);
    background: var(--diff-new-bg);
  }

  :deep(.pk-text) {
    font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
    font-size: 12px;
    font-weight: 600;
    color: var(--text);
  }

  // ===== 整行按操作着色 =====
  :deep(.row-updated) {
    td { background-color: var(--diff-updated-bg); }
  }

  :deep(.row-added) {
    td { background-color: var(--diff-added-bg); }
  }

  :deep(.row-removed) {
    td { background-color: var(--diff-removed-bg); }
  }

  // ===== 表头 =====
  :deep(.field-header) {
    font-weight: 600;
    text-align: center;
  }

  :deep(.sub-header) {
    font-size: 12px;
    color: var(--text-secondary);
  }

  // ===== 表格外框（表头+单元格，细浅框线）=====
  :deep(.n-data-table-th),
  :deep(.n-data-table-td) {
    border: 0.5px solid var(--diff-grid) !important;
  }

  // ===== 固定列不透明（防穿透）=====
  :deep(.n-data-table-td--fixed-left),
  :deep(.n-data-table-td--fixed-right) {
    background-color: var(--card) !important;
  }

  // 着色行的固定列跟随行背景：用 card 底 + tint 叠加渐变，亮暗主题都成立
  :deep(.row-updated .n-data-table-td--fixed-left),
  :deep(.row-updated .n-data-table-td--fixed-right) {
    background-color: var(--card) !important;
    background-image: linear-gradient(var(--diff-updated-bg), var(--diff-updated-bg));
  }

  :deep(.row-added .n-data-table-td--fixed-left),
  :deep(.row-added .n-data-table-td--fixed-right) {
    background-color: var(--card) !important;
    background-image: linear-gradient(var(--diff-added-bg), var(--diff-added-bg));
  }

  :deep(.row-removed .n-data-table-td--fixed-left),
  :deep(.row-removed .n-data-table-td--fixed-right) {
    background-color: var(--card) !important;
    background-image: linear-gradient(var(--diff-removed-bg), var(--diff-removed-bg));
  }

  :deep(.n-data-table-tr:hover > .n-data-table-td--fixed-left),
  :deep(.n-data-table-tr:hover > .n-data-table-td--fixed-right) {
    background-color: var(--bg-subtle) !important;
    background-image: none;
  }

  :deep(.n-data-table-th--fixed-left),
  :deep(.n-data-table-th--fixed-right) {
    background-color: var(--bg-subtle) !important;
  }

  // ===== 详情抽屉 =====
  :deep(.diff-cell-row) {
    td {
      background-color: var(--diff-old-bg) !important;
    }
  }

  :deep(.detail-name) {
    font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
    font-size: 12px;
    color: var(--text-secondary);
  }
}
</style>
