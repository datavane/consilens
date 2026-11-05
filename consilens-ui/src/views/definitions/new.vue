<script setup>
import { ref, reactive } from "vue";
import { useRouter, useRoute } from "vue-router";
import { useMessage } from "naive-ui";
import { NButton, NIcon } from "naive-ui";
import { ArrowBackOutline, ArrowForwardOutline, SaveOutline } from "@vicons/ionicons5";
import { createTaskDefinition, updateTaskDefinition, getTaskDefinition, runTaskDefinition } from "@/api/modules";
import { generateSerialNo } from "@/utils/format";
import { DEFAULT_DS_TYPE } from "@/common/datasourceDefaults";
import { useDatasourceSides } from "@/composables/useDatasourceSides";
import { useDatasourceQuickCreate } from "@/composables/useDatasourceQuickCreate";
import { useDefinitionDraft } from "@/composables/useDefinitionDraft";
import { useDefinitionConfig } from "@/composables/useDefinitionConfig";
import { usePlatformProperties, defaultPlatformForm } from "@/composables/usePlatformProperties";
import DefinitionWizardStepper from "./DefinitionWizardStepper.vue";
import DatasourceQuickCreateModal from "./DatasourceQuickCreateModal.vue";
import StepDatasource from "./steps/StepDatasource.vue";
import StepKeys from "./steps/StepKeys.vue";
import StepAdvanced from "./steps/StepAdvanced.vue";
import StepConfirm from "./steps/StepConfirm.vue";

const router = useRouter();
const route = useRoute();
const message = useMessage();

const currentStep = ref(1);
const submitting = ref(false);

const steps = [
  { title: "数据源", desc: "源端与目标端信息" },
  { title: "比对键", desc: "对齐两表的键列" },
  { title: "高级选项", desc: "超时与试运行" },
  { title: "确认", desc: "生成方案并提交" },
];

// ===== Step 1: 数据源 =====
function makeSide() {
  return {
    datasourceId: null,
    type: DEFAULT_DS_TYPE,
    mode: "table", // table | sql
    table: "",
    query: "",
    database: "",
    databases: [],
    tables: [],
    databasesLoading: false,
    tablesLoading: false,
    columns: [],
    columnsLoading: false,
  };
}

// 表单默认值工厂：声明与「清空草稿」共用，避免两处默认值漂移
function defaultPlanForm() {
  return {
    goal: "生成源表与目标表的数据对比配置",
    source: makeSide(),
    target: makeSide(),
    keys: [],
  };
}

function defaultAdvForm() {
  return {
    mode: "checksum",          // strategy.mode: checksum | join
    algorithm: "concat",       // strategy.algorithm / checksumAlgorithm
    bisectionFactor: 4,        // strategy.bisectionFactor
    bisectionThreshold: 5000,  // strategy.bisectionThreshold
    batchSize: 1000,           // strategy.batchSize（透传 attributes）
    localCompareMode: "full",  // strategy.localCompare.mode: full | auto
    validateUniqueKeys: true,  // 校验唯一键
    allowNullableKeys: false,  // 允许可空键列（默认拒绝）
    maxDifferences: null,      // 最大差异数（可选）
    decimalPrecision: null,    // normalization.global.decimal.precision
    timezone: "",              // normalization.global.{timestamp,datetime}.timezone
  };
}

const planForm = reactive(defaultPlanForm());
const compareForm = reactive({
  keyMappings: [], // [{source, target}] 主键映射
  fieldMappings: [], // [{source, target}] 比较字段映射
  ignoreColumns: [], // [name] 排除字段
});

const {
  datasources,
  datasourceOptions,
  loadDatasources,
  handleDatasourceChange,
  handleSideDatabaseChange,
  handleSideTableChange,
  loadSideColumns,
  sourceColumnOptions,
  targetColumnOptions,
  ignoreColumnOptions,
  restoreSideSelects,
} = useDatasourceSides({
  planForm,
  message,
  // 换数据源/表后旧列名映射必须失效，否则会以上一张表的列提交
  onStructureChange: () => {
    compareForm.keyMappings = [];
    compareForm.fieldMappings = [];
    compareForm.ignoreColumns = [];
  },
});

const {
  dsModal,
  dsFormRef,
  dsTypeOptions,
  openDsModal,
  handleDsTest,
  handleDsCreate,
} = useDatasourceQuickCreate({
  planForm,
  datasources,
  loadDatasources,
  handleDatasourceChange,
  message,
});

// 快捷创建弹框内部表单实例回写（测试连接/创建动作从组合式函数读取）
function setDsFormRef(instance) {
  dsFormRef.value = instance;
}

// ===== Step 3: 高级选项 =====
const platformForm = reactive(defaultPlatformForm());
const { platformProperties, restorePlatformProperties } = usePlatformProperties(platformForm);

const runForm = reactive({
  serialNo: generateSerialNo(),
  timeoutMs: 300000,
  dryRun: false,
});

// ===== 比对策略高级选项（对齐 CLI strategy 配置）=====
const advForm = reactive(defaultAdvForm());

// ===== Step 4: 确认（Plan → Validate → Execute）=====
function sideHasInput(side) {
  if (planForm[side].mode === "sql") {
    return Boolean(planForm[side].query.trim());
  }
  return Boolean(planForm[side].table.trim());
}

// ===== Step 4: 保存定义 =====
const defForm = reactive({
  name: "",
  description: "",
  runNow: true,
});

const { buildConfig, configDisplay, summary, validateMappings } = useDefinitionConfig({
  planForm,
  compareForm,
  advForm,
  runForm,
  platformForm,
  platformProperties,
});

// ===== 编辑模式（?editId=）=====
const editId = ref(route.query.editId || "");

function resetForms() {
  Object.assign(planForm, defaultPlanForm());
  Object.assign(compareForm, { keyMappings: [], fieldMappings: [], ignoreColumns: [] });
  Object.assign(advForm, defaultAdvForm());
  Object.assign(platformForm, defaultPlatformForm());
  Object.assign(runForm, { serialNo: generateSerialNo(), timeoutMs: 300000, dryRun: false });
  Object.assign(defForm, { name: "", description: "", runNow: true });
  currentStep.value = 1;
}

const { draftRestored, restoreDraft, removeStoredDraft, clearDraft } = useDefinitionDraft({
  editId,
  currentStep,
  planForm,
  compareForm,
  advForm,
  platformForm,
  runForm,
  defForm,
  resetForms,
  message,
});

async function loadForEdit() {
  if (!editId.value) return;
  try {
    const definition = await getTaskDefinition(editId.value);
    const config = definition.config || {};
    planForm.goal = config.goal || planForm.goal;
    const fillSide = (side, endpoint) => {
      if (!endpoint) return;
      planForm[side].type = endpoint.type || DEFAULT_DS_TYPE;
      planForm[side].table = endpoint.table || "";
      planForm[side].query = endpoint.query || "";
      planForm[side].database = endpoint.database || "";
      planForm[side].datasourceId = endpoint.datasourceId != null ? String(endpoint.datasourceId) : null;
      planForm[side].mode = endpoint.table ? "table" : "sql";
    };
    fillSide("source", config.source);
    fillSide("target", config.target);
    // 级联下拉回显：加载数据库/表/字段列表（数据源引用 + 库表名已就位）
    await Promise.all([
      restoreSideSelects("source"),
      restoreSideSelects("target"),
    ]);
    const comparison = config.comparison || {};
    // 主键映射优先取 comparison.keyMappings / hints.keyMappings：两端列名不同时
    // config.keys 只保存源列，直接用它回填会把目标列也覆盖成源列，保存后映射被改坏。
    const savedKeyMappings = comparison.keyMappings || config.hints?.keyMappings;
    compareForm.keyMappings = Array.isArray(savedKeyMappings) && savedKeyMappings.length
      ? savedKeyMappings.map((m) => ({ source: m.source, target: m.target }))
      : (config.keys || []).map((key) => ({ source: key, target: key }));
    if (comparison.fieldMappings?.length) {
      compareForm.fieldMappings = comparison.fieldMappings.map((m) => ({ source: m.source, target: m.target }));
    } else if (comparison.fields?.length) {
      compareForm.fieldMappings = comparison.fields.map((field) => ({ source: field, target: field }));
    }
    compareForm.ignoreColumns = comparison.ignoreColumns || [];
    const exec = config.executionOptions || {};
    const strategyPref = config.hints?.strategyPreference || {};
    // 后端计划名是 pushdown-checksum / server-join，下拉选项是 checksum / join：
    // 不能直接 toLowerCase，否则编辑回显时「比对模式」匹配不到选项而显示空白
    const preferredPlan = (strategyPref.preferredPlans?.[0] || "checksum").toLowerCase();
    advForm.mode = preferredPlan.includes("join") ? "join" : "checksum";
    advForm.algorithm = exec.checksumAlgorithm || "concat";
    advForm.bisectionFactor = exec.bisectionFactor ?? 4;
    advForm.bisectionThreshold = exec.bisectionThreshold ?? 5000;
    advForm.batchSize = exec.batchSize ?? 1000;
    advForm.localCompareMode = exec.localCompareMode || "full";
    advForm.validateUniqueKeys = exec.validateUniqueKeys ?? true;
    advForm.allowNullableKeys = exec.allowNullableKeys ?? false;
    advForm.maxDifferences = exec.maxDifferences ?? null;
    runForm.timeoutMs = exec.timeoutMs ?? 300000;
    const normalization = config.normalization || config.hints?.normalization || {};
    advForm.decimalPrecision = normalization.global?.decimal?.precision ?? null;
    advForm.timezone = normalization.global?.timestamp?.timezone
      || normalization.global?.datetime?.timezone || "";
    platformForm.platform = config.platform || "local";
    const savedProperties = config.properties || config.cluster?.[platformForm.platform] || {};
    restorePlatformProperties(savedProperties);
    defForm.name = definition.name;
    defForm.description = definition.description || "";
    // 编辑默认回到第一步，便于逐项确认与修改
    currentStep.value = 1;
    if (!planForm.source.datasourceId && !planForm.target.datasourceId) {
      message.info("该定义未引用数据源，请回到第一步选择数据源以完成字段配置");
    }
  } catch (error) {
    message.error(error?.response?.data?.error || "加载定义失败");
  }
}

async function handleSaveDefinition() {
  // 保存 + 可选运行：重复点击会创建重复定义/实例，入口直接去重
  if (submitting.value) return;
  if (!defForm.name.trim()) {
    message.warning("请填写定义名称");
    return;
  }
  const mappingProblem = validateMappings();
  if (mappingProblem) {
    message.warning(mappingProblem);
    return;
  }
  submitting.value = true;
  let definition;
  try {
    const payload = {
      name: defForm.name.trim(),
      description: defForm.description.trim() || undefined,
      config: buildConfig(),
    };
    definition = editId.value
      ? await updateTaskDefinition(editId.value, payload)
      : await createTaskDefinition(payload);
  } catch (error) {
    message.error(error?.response?.data?.error || error?.message || "保存失败");
    submitting.value = false;
    return;
  }
  // 定义已经保存成功：先清草稿，后续运行失败不能让用户以为“没保存”而重复创建
  removeStoredDraft();
  if (defForm.runNow) {
    try {
      const accepted = await runTaskDefinition(definition.id, {
        serialNo: runForm.serialNo,
        options: {
          dryRun: runForm.dryRun,
          timeoutMs: runForm.timeoutMs || undefined,
        },
      });
      message.success(`${editId.value ? "已更新定义" : "已保存定义"}并提交运行：${accepted.taskId}`);
      router.push(`/instances/${accepted.taskId}`);
    } catch (error) {
      message.warning(`定义已${editId.value ? "更新" : "保存"}，但运行提交失败：`
        + (error?.response?.data?.error || error?.message || "未知错误"));
      router.push(`/definitions/${definition.id}`);
    }
  } else {
    message.success(`定义「${definition.name}」已${editId.value ? "更新" : "保存"}`);
    router.push(`/definitions/${definition.id}`);
  }
  submitting.value = false;
}

async function goNext() {
  if (currentStep.value === 1) {
    if (!planForm.source.datasourceId || !planForm.target.datasourceId) {
      message.warning("请为两端选择数据源");
      return;
    }
    if (!sideHasInput("source") || !sideHasInput("target")) {
      message.warning("请填写两端表名或查询");
      return;
    }
  } else if (currentStep.value === 2) {
    const mappingProblem = validateMappings();
    if (mappingProblem) {
      message.warning(mappingProblem);
      return;
    }
  }
  currentStep.value += 1;
  if (currentStep.value === 2) {
    // 进入比对键页：加载两端字段
    loadSideColumns("source");
    loadSideColumns("target");
  }
}

function goPrev() {
  if (currentStep.value > 1) currentStep.value -= 1;
}

// 允许点击跳回已完成的步骤
function clickStep(index) {
  if (index + 1 < currentStep.value) {
    currentStep.value = index + 1;
  }
}

// 进入页面时预加载数据源列表；编辑模式加载定义；新建模式恢复草稿
async function initializePage() {
  if (editId.value) {
    // 编辑回填依赖 datasources 列表（restoreSideSelects 会按 id 查找并加载库表），
    // 必须先等列表就绪，否则下拉框只有回显值没有可选项。
    await loadDatasources();
    await loadForEdit();
  } else {
    // 失败要给提示并可重试：静默失败会让第 1 步的数据源下拉永远空白
    loadDatasources().catch(() => {
      message.warning("数据源列表加载失败，请刷新页面重试");
    });
    draftRestored.value = restoreDraft();
    if (draftRestored.value) {
      message.success("已恢复上次未提交的草稿");
    }
  }
}
// 不用 top-level await：<script setup> 顶层 await 会让组件变成异步组件，
// 未包 <Suspense> 时整页不渲染。
initializePage();
</script>

<template>
  <div class="page-container">
    <div class="wizard-wrap">
      <!-- 页头 -->
      <div class="wizard-head">
        <div class="wizard-head-row">
          <a class="back-link" @click="router.push('/definitions')">
            <n-icon size="14"><ArrowBackOutline /></n-icon>
            返回定义列表
          </a>
          <a v-if="draftRestored && !editId" class="draft-clear" @click="clearDraft">清空草稿</a>
        </div>
        <h1 class="wizard-title">{{ editId ? "编辑定义" : "新建定义" }}</h1>
        <p class="wizard-subtitle">四步保存一个可复用的数据校验任务定义</p>
      </div>

      <!-- 步骤条 -->
      <DefinitionWizardStepper :steps="steps" :current-step="currentStep" @step-click="clickStep" />

      <!-- Step 1: 数据源 -->
      <StepDatasource
        v-show="currentStep === 1"
        :plan-form="planForm"
        :datasource-options="datasourceOptions"
        @open-ds-modal="openDsModal"
        @datasource-change="handleDatasourceChange"
        @database-change="handleSideDatabaseChange"
        @table-change="handleSideTableChange"
      />

      <!-- Step 2: 比对键与字段 -->
      <StepKeys
        v-show="currentStep === 2"
        :plan-form="planForm"
        :compare-form="compareForm"
        :source-column-options="sourceColumnOptions"
        :target-column-options="targetColumnOptions"
        :ignore-column-options="ignoreColumnOptions"
      />

      <!-- Step 3: 高级选项 -->
      <StepAdvanced
        v-show="currentStep === 3"
        :adv-form="advForm"
        :run-form="runForm"
        :platform-form="platformForm"
      />

      <!-- Step 4: 保存定义 -->
      <StepConfirm
        v-show="currentStep === 4"
        :def-form="defForm"
        :summary="summary"
        :config-display="configDisplay"
      />

      <!-- 底部操作栏（固定，卡片内部滚动） -->
      <div class="step-bar">
        <div class="step-bar-inner">
          <n-button v-if="currentStep > 1" size="small" @click="goPrev">
            <template #icon>
              <n-icon><ArrowBackOutline /></n-icon>
            </template>
            上一步
          </n-button>
          <div class="flex-1" />
          <template v-if="currentStep < 4">
            <n-button type="primary" size="small" @click="goNext">
              下一步
              <template #icon>
                <n-icon><ArrowForwardOutline /></n-icon>
              </template>
            </n-button>
          </template>
          <template v-else>
            <n-button type="primary" size="small" :loading="submitting" @click="handleSaveDefinition">
              <template #icon>
                <n-icon><SaveOutline /></n-icon>
              </template>
              {{ defForm.runNow ? "保存并运行" : "保存定义" }}
            </n-button>
          </template>
        </div>
      </div>
      </div>

      <!-- 新建数据源弹框 -->
      <DatasourceQuickCreateModal
        :ds-modal="dsModal"
        :type-options="dsTypeOptions"
        :set-form-ref="setDsFormRef"
        @test="handleDsTest"
        @create="handleDsCreate"
      />
  </div>
</template>

<style scoped lang="scss">
// ===== 页面骨架：宽栏居中，滚动限制在步骤卡片内部 =====
.page-container {
  height: 100vh;
  box-sizing: border-box;
  overflow: hidden;
}

.wizard-wrap {
  width: 100%;
  max-width: 1200px;
  margin: 0 auto;
  height: 100%;
  display: flex;
  flex-direction: column;
}

.wizard-head {
  flex-shrink: 0;

  .wizard-head-row {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 12px;
  }

  .back-link {
    display: inline-flex;
    align-items: center;
    gap: 4px;
    font-size: 12.5px;
    color: var(--text-muted);
    cursor: pointer;
    text-decoration: none;

    &:hover {
      color: var(--text-secondary);
    }
  }

  .draft-clear {
    font-size: 12px;
    color: var(--text-muted);
    cursor: pointer;

    &:hover {
      color: var(--danger);
    }
  }

  .wizard-title {
    margin: 8px 0 2px;
    font-size: 16px;
    font-weight: 600;
    line-height: 24px;
    color: var(--text);
  }

  .wizard-subtitle {
    margin: 0;
    font-size: 12.5px;
    color: var(--text-muted);
  }
}

// ===== 底部操作栏（固定） =====
.step-bar {
  flex-shrink: 0;
  padding: 14px 0 0;
  border-top: 1px solid var(--border);
}

.step-bar-inner {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 0 4px 4px;
}

</style>
