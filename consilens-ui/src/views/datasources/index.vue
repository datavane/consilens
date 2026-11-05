<script setup>
import { ref, reactive, computed, h, onMounted, watch, nextTick } from "vue";
import { useMessage, useDialog } from "naive-ui";
import { NIcon, NTag } from "naive-ui";
import { AddOutline, LinkOutline, CubeOutline, GridOutline, ListOutline, ServerOutline, RefreshOutline } from "@vicons/ionicons5";
import {
  listDatasourceTypes,
  getDatasourceTypeConfig,
  listDatasources,
  createDatasource,
  updateDatasource,
  deleteDatasource,
  testDatasource,
  testConnection,
  listDatasourceDatabases,
  listDatasourceTables,
  listDatasourceColumns,
} from "@/api/modules";
import DataSourceForm from "@/components/DataSourceForm.vue";
import EmptyState from "@/components/common/EmptyState.vue";
import SkeletonTable from "@/components/common/SkeletonTable.vue";
import CopyableId from "@/components/common/CopyableId.vue";
import TimeText from "@/components/common/TimeText.vue";
import { DEFAULT_DS_TYPE, FALLBACK_DS_FIELDS, collectDsParam, splitTestOptions } from "@/common/datasourceDefaults";
import { datasourceTypeColor } from "@/common/statusMapping";
import { useListQuerySync } from "@/composables/useListQuerySync";

const message = useMessage();
const dialog = useDialog();

// ===== 列表 =====
// 数据源数量少，后端也提供了不分页的列表接口：这里一次拉全量，筛选与分页在本地完成
// （数量增长后需改回分页接口 + 服务端筛选；与运行实例列表同样的筛选交互）
const loading = ref(false);
const loadError = ref(false);
const items = ref([]);
const types = ref([]);
const pagination = reactive({
  page: 1,
  pageSize: 10,
  itemCount: 0,
  pageSizes: [10, 20, 50],
  showSizePicker: true,
  prefix: ({ itemCount }) => `共 ${itemCount} 条`,
});

// 筛选与 URL 双向同步：初始从 query 恢复，每次加载写回（与其它列表页同一套交互）
const filters = reactive({ keyword: "", type: null, startTime: null, endTime: null });

const { filtersActive, syncToUrl } = useListQuerySync(filters, pagination, {
  keyword: {},
  type: {},
  startTime: { parse: (v) => Number(v) || null, serialize: String },
  endTime: { parse: (v) => Number(v) || null, serialize: String },
});

function timeOf(value) {
  if (!value) return null;
  const t = new Date(value).getTime();
  return Number.isNaN(t) ? null : t;
}

const filteredItems = computed(() => {
  const keyword = filters.keyword.trim().toLowerCase();
  const start = timeOf(filters.startTime);
  const end = timeOf(filters.endTime);
  return items.value.filter((item) => {
    if (keyword && !String(item.name || "").toLowerCase().includes(keyword)) return false;
    if (filters.type && item.type !== filters.type) return false;
    const created = timeOf(item.createdAt);
    if (start && (created === null || created < start)) return false;
    if (end && (created === null || created > end)) return false;
    return true;
  });
});

async function loadTypes() {
  try {
    types.value = await listDatasourceTypes();
  } catch {
    types.value = [];
  }
}

async function loadData() {
  loading.value = true;
  loadError.value = false;
  syncToUrl();
  try {
    const result = await listDatasources();
    items.value = Array.isArray(result) ? result : result?.items || [];
  } catch {
    loadError.value = true;
    items.value = [];
  } finally {
    loading.value = false;
  }
}

// 本地筛选 + 分页：itemCount 跟随筛选结果，页码越界时回退到最后一页避免空页
watch(
  filteredItems,
  (list) => {
    pagination.itemCount = list.length;
    const maxPage = Math.max(1, Math.ceil(list.length / pagination.pageSize));
    if (pagination.page > maxPage) pagination.page = maxPage;
  },
  { immediate: true }
);

function handleSearch() {
  pagination.page = 1;
  loadData();
}

function handleReset() {
  Object.assign(filters, { keyword: "", type: null, startTime: null, endTime: null });
  handleSearch();
}

// ===== 新建 / 编辑 =====
const modal = reactive({
  show: false,
  editing: false,
  id: null,
  saving: false,
  testing: false,
  testResult: null,
  fields: [], // 当前类型的连接参数模板（来自后端）
  values: {}, // 模板字段值（字符串）
  prefill: null, // 编辑回填的原始参数
  form: {
    name: "",
    type: "",
  },
});

// 动态表单实例（validateAndGetValues / resetValues / setValues）
const dsFormRef = ref(null);

// 名称输入框（弹窗打开时自动聚焦）
const nameInputRef = ref(null);

// 拉取类型模板：失败时使用兜底模板；序号防止快速切换类型时竞态覆盖
let configSeq = 0;
async function loadDsConfig(type, prefill) {
  const seq = ++configSeq;
  modal.fields = [];
  modal.values = {};
  let fields = FALLBACK_DS_FIELDS;
  try {
    const config = await getDatasourceTypeConfig(type);
    if (seq !== configSeq) return;
    fields = Array.isArray(config) && config.length ? config : FALLBACK_DS_FIELDS;
  } catch {
    if (seq !== configSeq) return;
  }
  modal.fields = fields;
  // 等 fields 生效（组件已补齐默认值）后再回填，prefill 有值优先
  await nextTick();
  if (seq !== configSeq) return;
  // prefill 只在"打开弹窗的第一次"用一次：切换类型时若继续拿它覆盖，
  // 用户已经改过的 host/端口/库名会被静默改回打开时的旧值（切回去就"丢"了编辑）。
  dsFormRef.value?.setValues(prefill || {});
  if (prefill) modal.prefill = null;
}

// 切换类型：重置模板与表单值
watch(
  () => modal.form.type,
  (type) => {
    if (!modal.show || !type) return;
    // 换了类型就是另一组连接参数，上一次"连接成功"不再代表当前配置
    modal.testResult = null;
    loadDsConfig(type, modal.prefill);
  }
);

function openCreate() {
  modal.editing = false;
  modal.id = null;
  modal.testResult = null;
  modal.prefill = null;
  const defaultType = types.value.find((item) => item.type === DEFAULT_DS_TYPE)?.type
    || types.value[0]?.type
    || DEFAULT_DS_TYPE;
  modal.form = { name: "", type: defaultType };
  modal.show = true;
  // type 未变化时 watch 不触发，这里显式加载模板
  loadDsConfig(defaultType, null);
  nextTick(() => nameInputRef.value?.focus());
}

/**
 * 表单任一字段变化都作废上一次"连接成功"：否则改完 host/密码后绿色结果还在，
 * 用户会以为当前这组参数已经连通（保存并不校验测试结果）。
 */
function handleFormChanged() {
  if (modal.testResult) modal.testResult = null;
}

function openEdit(row) {
  modal.editing = true;
  modal.id = row.id;
  modal.testResult = null;
  const param = row.param || {};
  // 按参数已有键回填（host/port/database/username/password 一定有；sid/schema/properties 有则回填）
  modal.prefill = {};
  for (const [key, value] of Object.entries(param)) {
    // 密码不出接口：跳过，留空保存时后端保留原密码
    if (key === "password") continue;
    if (value !== undefined && value !== null && value !== "") {
      modal.prefill[key] = String(value);
    }
  }
  // 密码不出接口：不放入 prefill，留空保存时后端保留原密码
  modal.form = { name: row.name, type: row.type };
  modal.show = true;
  // type 未变化时 watch 不触发，这里显式加载模板并回填
  loadDsConfig(row.type, modal.prefill);
  nextTick(() => nameInputRef.value?.focus());
}

async function handleSave() {
  if (modal.saving) return;
  if (!modal.form.name.trim()) {
    message.warning("请填写名称");
    return;
  }
  // 从校验开始就置为 in-flight，双击不会各自通过校验后发起两次创建/更新
  modal.saving = true;
  try {
    const { valid, values } = (await dsFormRef.value?.validateAndGetValues()) || { valid: false, values: null };
    if (!valid) return;
    const payload = {
      name: modal.form.name.trim(),
      type: modal.form.type,
      param: collectDsParam(values, modal.fields),
    };
    if (modal.editing) {
      await updateDatasource(modal.id, payload);
      message.success("数据源已更新");
    } else {
      await createDatasource(payload);
      message.success("数据源已创建");
    }
    modal.show = false;
    loadData();
  } catch (error) {
    message.error(error?.response?.data?.error || "保存失败");
  } finally {
    modal.saving = false;
  }
}

async function handleModalTest() {
  modal.testing = true;
  modal.testResult = null;
  try {
    const result = await testConnectionLocal();
    if (!result) return; // 必填校验未通过，不展示结果
    modal.testResult = { ok: result.success, text: result.error || "连接成功" };
  } catch (error) {
    modal.testResult = { ok: false, text: error?.response?.data?.error || "连接失败" };
  } finally {
    modal.testing = false;
  }
}

async function testConnectionLocal() {
  const { valid, values } = (await dsFormRef.value?.validateAndGetValues()) || { valid: false, values: null };
  if (!valid) return null;
  // 始终测试表单里的当前值；编辑态密码不回显，留空时由服务端回填已保存密码。
  const param = collectDsParam(values, modal.fields);
  const options = splitTestOptions(param);
  return testConnection({
    type: modal.form.type,
    ...param,
    options,
    ...(modal.editing && modal.id ? { datasourceId: modal.id } : {}),
  });
}

async function handleRowTest(row) {
  try {
    const result = await testDatasource(row.id);
    if (result.success) {
      message.success("连接成功");
    } else {
      message.error(result.error || "连接失败");
    }
  } catch (error) {
    message.error(error?.response?.data?.error || "连接失败");
  }
}

function handleDelete(row) {
  dialog.warning({
    title: "删除数据源",
    content: `确认删除数据源「${row.name}」吗？该操作不可恢复。`,
    positiveText: "删除",
    negativeText: "取消",
    onPositiveClick: async () => {
      try {
        await deleteDatasource(row.id);
        message.success("已删除");
        loadData();
      } catch (error) {
        message.error(error?.response?.data?.error || "删除失败");
      }
    },
  });
}

// ===== 浏览（元数据树）=====
const browse = reactive({
  show: false,
  loading: false,
  current: null,
  data: [],
  columns: [],
  selectedTable: "",
});

let browseSeq = 0;

async function openBrowse(row) {
  const seq = ++browseSeq;
  // 切换数据源时让在途的列查询失效，避免旧表的列信息写进新抽屉。
  columnsSeq++;
  browse.current = row;
  browse.data = [];
  browse.columns = [];
  browse.selectedTable = "";
  browse.show = true;
  browse.loading = true;
  try {
    const databases = await listDatasourceDatabases(row.id);
    if (seq !== browseSeq) return;
    browse.data = databases.map((name) => ({
      key: `db::${name}`,
      label: name,
      isLeaf: false,
      database: name,
      // 不提供 children：懒加载（展开时触发 on-load 获取表）
    }));
  } catch (error) {
    if (seq !== browseSeq) return;
    message.error(error?.response?.data?.error || "获取数据库列表失败");
    browse.data = [];
  } finally {
    if (seq === browseSeq) {
      browse.loading = false;
    }
  }
}

async function loadTreeNode(node) {
  if (node.isLeaf) return;
  const id = browse.current.id;
  try {
    await loadTreeNodeChildren(node, id);
  } catch (error) {
    // naive-ui 只把这些错误打到 console：节点会停在"展开但空白"，用户看不出发生了什么
    node.children = [];
    message.error(error?.response?.data?.error || `加载「${node.label || node.database}」失败`);
  }
}

async function loadTreeNodeChildren(node, id) {
  if (node.database && !node.table) {
    const tables = await listDatasourceTables(id, node.database);
    node.children = tables.map((name) => ({
      key: `tbl::${node.database}::${name}`,
      label: name,
      isLeaf: false,
      database: node.database,
      table: name,
    }));
  } else if (node.table) {
    const columns = await listDatasourceColumns(id, node.database, node.table);
    node.children = columns.map((column) => ({
      key: `col::${node.database}::${node.table}::${column.name}`,
      label: `${column.name}  ${column.dataType || ""}`.trim(),
      isLeaf: true,
    }));
  }
}

let columnsSeq = 0;

// 点击（选中）表节点时，在右侧加载列信息
async function handleTreeSelect(keys) {
  const key = Array.isArray(keys) ? keys[0] : null;
  if (!key || !key.startsWith("tbl::")) {
    return;
  }
  const [, database, table] = key.split("::");
  if (!database || !table || !browse.current) return;
  const seq = ++columnsSeq;
  browse.selectedTable = table;
  browse.columns = [];
  try {
    const columns = await listDatasourceColumns(browse.current.id, database, table);
    if (seq !== columnsSeq) return;
    browse.columns = columns;
  } catch (error) {
    if (seq !== columnsSeq) return;
    message.error(error?.response?.data?.error || "获取列信息失败");
  }
}

// 树节点图标：库 / 表 / 列
function renderTreeLabel({ option }) {
  let icon = ListOutline;
  if (option.key?.startsWith("db::")) icon = CubeOutline;
  else if (option.key?.startsWith("tbl::")) icon = GridOutline;
  return h("span", { class: "tree-label" }, [
    h(NIcon, { size: 15, class: "tree-icon" }, { default: () => h(icon) }),
    h("span", { class: "tree-text" }, option.label),
  ]);
}

const columnColumns = [
  {
    title: "列名",
    key: "name",
    minWidth: 140,
    render: (row) => h(CopyableId, { value: row.name, length: 20 }),
  },
  { title: "类型", key: "dataType", width: 120, render: (row) => h("span", { class: "text-secondary" }, row.dataType || "-") },
  {
    title: "可空",
    key: "nullable",
    width: 80,
    render: (row) =>
      row.nullable === null || row.nullable === undefined
        ? h("span", { class: "text-muted" }, "-")
        : h(NTag, { size: "small", bordered: false, type: row.nullable ? "default" : "info" }, row.nullable ? "可空" : "非空"),
  },
];

const columns = [
  {
    title: "名称",
    key: "name",
    minWidth: 200,
    // 窄屏下名称会被截断：补 tooltip，避免用户看不到完整数据源名
    ellipsis: { tooltip: true },
    render: (row) =>
      h(
        "a",
        { class: "app-name-link ds-name-link", onClick: () => openBrowse(row) },
        [
          // naive NIcon 的 color 走 props（style 会被组件内部吞掉）
          h(
            NIcon,
            { size: 15, class: "ds-icon", color: datasourceTypeColor(row.type) },
            { default: () => h(LinkOutline) }
          ),
          h("span", row.name),
        ]
      ),
  },
  {
    title: "类型",
    key: "type",
    width: 120,
    render: (row) => h(NTag, { size: "small", bordered: false }, row.type),
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
    width: 220,
    render: (row) =>
      h("div", { class: "row-actions" }, [
        h("a", { class: "row-action", onClick: () => handleRowTest(row) }, "测试连接"),
        h("a", { class: "row-action", onClick: () => openBrowse(row) }, "浏览"),
        h("a", { class: "row-action", onClick: () => openEdit(row) }, "编辑"),
        h("a", { class: "row-action-danger", onClick: () => handleDelete(row) }, "删除"),
      ]),
  },
];

const typeOptions = computed(() => types.value.map((item) => ({
  label: item.type,
  value: item.type,
})));

// 筛选栏的类型下拉（优先用后端返回的展示名）
const typeFilterOptions = computed(() =>
  types.value.map((item) => ({ label: item.label || item.type, value: item.type }))
);

onMounted(() => {
  loadTypes();
  loadData();
});
</script>

<template>
  <div class="page-container">
    <div class="page-header">
      <div>
        <div class="page-title">数据源</div>
        <div class="page-desc">管理数据连接，供任务创建时直接选用，支持实时浏览库 / 表 / 列</div>
      </div>
      <div class="head-actions">
        <n-button type="primary" size="small" @click="openCreate">
          <template #icon>
            <n-icon><AddOutline /></n-icon>
          </template>
          新建数据源
        </n-button>
      </div>
    </div>

    <n-card size="small" :bordered="true" class="mt-12 mb-12">
      <div class="filter-bar">
        <n-input
          v-model:value="filters.keyword"
          placeholder="数据源名称"
          clearable
          size="small"
          class="filter-item"
          @keyup.enter="handleSearch"
        />
        <n-select
          v-model:value="filters.type"
          :options="typeFilterOptions"
          placeholder="全部类型"
          clearable
          size="small"
          class="filter-item"
        />
        <n-date-picker
          v-model:value="filters.startTime"
          type="datetime"
          clearable
          size="small"
          class="filter-item"
          placeholder="创建开始时间"
        />
        <n-date-picker
          v-model:value="filters.endTime"
          type="datetime"
          clearable
          size="small"
          class="filter-item"
          placeholder="创建结束时间"
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
      <SkeletonTable v-if="loading && !items.length" :rows="8" />
      <EmptyState
        v-else-if="!loading && !filteredItems.length"
        class="flex-1"
        :title="loadError ? '加载失败' : filtersActive ? '没有匹配的数据源' : '还没有数据源'"
        :description="loadError ? '网络或服务异常，请重试' : filtersActive ? '尝试调整或清除筛选条件' : '新建数据源后即可在任务创建时直接选用'"
        :action="loadError ? '重试' : filtersActive ? '清除筛选' : '新建数据源'"
        @action="loadError ? loadData() : filtersActive ? handleReset() : openCreate()"
      />
      <n-data-table
        v-else
        class="main-table"
        :columns="columns"
        :data="filteredItems"
        :loading="loading"
        :row-key="(row) => row.id"
        flex-height
        :bordered="false"
        size="small"
        :pagination="pagination"
        @update:page="(page) => { pagination.page = page; }"
        @update:page-size="(size) => { pagination.pageSize = size; pagination.page = 1; }"
      />
    </n-card>

    <!-- 新建 / 编辑弹窗 -->
    <n-modal v-model:show="modal.show" preset="card" style="width: 640px" :title="modal.editing ? '编辑数据源' : '新建数据源'">
      <div class="ds-form">
        <div class="ds-row-top">
          <div class="field">
            <label class="field-label">名称</label>
            <n-input ref="nameInputRef" v-model:value="modal.form.name" placeholder="例如：生产订单库" size="small" />
          </div>
          <div class="field">
            <label class="field-label">类型</label>
            <n-select v-model:value="modal.form.type" :options="typeOptions" size="small" />
          </div>
        </div>
        <div class="ds-separator" />
        <data-source-form
          ref="dsFormRef"
          v-model:model-value="modal.values"
          :fields="modal.fields"
          :editing="modal.editing"
          @update:model-value="handleFormChanged"
        />
        <div v-if="modal.fields.length === 0" class="field text-muted" style="font-size: 12px">
          连接参数加载中…
        </div>
      </div>
      <template #footer>
        <div class="modal-footer">
          <div class="modal-footer-left">
            <n-button size="small" :loading="modal.testing" @click="handleModalTest">测试连接</n-button>
            <span v-if="modal.testResult" class="conn-result" :class="modal.testResult.ok ? 'ok' : 'fail'">
              <span class="conn-dot" />{{ modal.testResult.text }}
            </span>
          </div>
          <div class="modal-footer-right">
            <n-button size="small" @click="modal.show = false">取消</n-button>
            <n-button size="small" type="primary" :loading="modal.saving" @click="handleSave">
              {{ modal.editing ? "保存" : "创建" }}
            </n-button>
          </div>
        </div>
      </template>
    </n-modal>

    <!-- 浏览抽屉 -->
    <n-drawer v-model:show="browse.show" :width="780">
      <n-drawer-content :title="`浏览数据源：${browse.current?.name || ''}`" closable>
        <div class="browse-grid">
          <div class="browse-panel">
            <div class="browse-title">
              <n-icon size="15"><ServerOutline /></n-icon>
              <span>库 / 表</span>
              <span class="browse-count">{{ browse.data.length }}</span>
            </div>
            <n-spin :show="browse.loading">
              <n-tree
                v-if="browse.data.length"
                :data="browse.data"
                block-line
                :render-label="renderTreeLabel"
                :on-load="loadTreeNode"
                @update:selected-keys="handleTreeSelect"
              />
              <div v-else-if="!browse.loading" class="browse-hint">未获取到数据库列表</div>
            </n-spin>
          </div>
          <div class="browse-panel">
            <div class="browse-title">
              <n-icon size="15"><ListOutline /></n-icon>
              <span>列信息</span>
              <span v-if="browse.selectedTable" class="browse-table-tag">{{ browse.selectedTable }}</span>
              <span v-if="browse.columns.length" class="browse-count">{{ browse.columns.length }} 列</span>
            </div>
            <n-data-table
              :columns="columnColumns"
              :data="browse.columns"
              size="small"
              :bordered="false"
              :max-height="480"
            />
            <div v-if="!browse.columns.length" class="browse-hint">
              在左侧选择一张表以查看其列定义
            </div>
          </div>
        </div>
      </n-drawer-content>
    </n-drawer>
  </div>
</template>

<style scoped lang="scss">
.ds-form {
  padding-top: 4px;
}

.ds-row-top {
  display: grid;
  grid-template-columns: 1fr 200px;
  gap: 16px;
  align-items: start;
}

.ds-separator {
  height: 1px;
  margin: 4px 0 16px;
  background: var(--border);
}

.modal-footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.modal-footer-left {
  display: flex;
  align-items: center;
  gap: 10px;
  min-width: 0;
}

.modal-footer-right {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-shrink: 0;
}

.field {
  margin-bottom: 16px;
}

.field-label {
  display: block;
  margin-bottom: 6px;
  font-size: 12px;
  font-weight: 500;
  color: var(--text-secondary);
}

.conn-result {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 12px;
  word-break: break-all;

  &.ok {
    color: var(--success-text);

    .conn-dot {
      background: var(--success);
    }
  }

  &.fail {
    color: var(--danger);

    .conn-dot {
      background: var(--danger);
    }
  }
}

.conn-dot {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  flex-shrink: 0;
}

.browse-grid {
  display: grid;
  grid-template-columns: 320px 1fr;
  gap: 12px;
  height: 100%;
  align-items: stretch;
}

.browse-panel {
  background: var(--bg-subtle);
  border: 1px solid var(--border);
  border-radius: 8px;
  padding: 12px;
  min-height: 480px;
}

.browse-title {
  display: flex;
  align-items: center;
  gap: 7px;
  font-size: 13px;
  font-weight: 600;
  color: var(--text);
  margin-bottom: 10px;
  padding-bottom: 10px;
  border-bottom: 1px solid var(--border);
}

.browse-count {
  margin-left: auto;
  padding: 0 8px;
  font-size: 11px;
  font-weight: 500;
  color: var(--text-secondary);
  background: var(--card);
  border: 1px solid var(--border);
  border-radius: 5px;
}

.browse-table-tag {
  margin-left: auto;
  max-width: 60%;
  padding: 1px 10px;
  font-size: 11px;
  font-weight: 500;
  color: var(--primary-text);
  background: var(--primary-bg);
  border-radius: 5px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.browse-hint {
  margin-top: 24px;
  text-align: center;
  font-size: 12px;
  color: var(--text-muted);
}
</style>
