<script setup>
import { NButton, NSelect, NSpin } from "naive-ui";
import { useMessage } from "naive-ui";

const props = defineProps({
  planForm: { type: Object, required: true },
  compareForm: { type: Object, required: true },
  sourceColumnOptions: { type: Array, default: () => [] },
  targetColumnOptions: { type: Array, default: () => [] },
  ignoreColumnOptions: { type: Array, default: () => [] },
});

const message = useMessage();

function setMapping(listName, index, field, value) {
  props.compareForm[listName][index][field] = value;
}

function addMapping(listName, source, target) {
  props.compareForm[listName].push({ source: source || "", target: target || "" });
}

function removeMapping(listName, index) {
  props.compareForm[listName].splice(index, 1);
}

// 自动匹配两表同名字段作为比较字段（排除主键列）
function autoMatchFields() {
  const keyNames = new Set(props.compareForm.keyMappings.map((m) => m.source).filter(Boolean));
  const sourceNames = new Set(props.planForm.source.columns.map((c) => c.name));
  const targetNames = new Set(props.planForm.target.columns.map((c) => c.name));
  const matched = [...sourceNames].filter((name) => targetNames.has(name) && !keyNames.has(name));
  props.compareForm.fieldMappings = matched.map((name) => ({ source: name, target: name }));
}

// 自动把同名主键填入（优先 id，其次第一个同名字段；只取一列避免全选）
function autoMatchKeys() {
  const sourceNames = props.planForm.source.columns.map((c) => c.name);
  const targetNames = new Set(props.planForm.target.columns.map((c) => c.name));
  const matched = sourceNames.filter((name) => targetNames.has(name));
  if (!matched.length) {
    message.warning("两表没有同名字段，请手动选择主键");
    return;
  }
  const primary = matched.includes("id") ? "id" : matched[0];
  props.compareForm.keyMappings = [{ source: primary, target: primary }];
}
</script>

<template>
  <!-- Step 2: 比对键与字段 -->
  <div class="panel">
    <div class="panel-head">
      <h2 class="panel-title">比对键与字段</h2>
      <p class="panel-desc">配置主键对齐、比较字段一一对应与排除字段</p>
    </div>
    <n-spin :show="planForm.source.columnsLoading || planForm.target.columnsLoading">
      <div class="section">
        <div class="section-head">
          <div class="section-title">主键列</div>
          <div class="section-tip">用于对齐两表行：源端缺失记为新增，目标端缺失记为删除</div>
        </div>
        <div v-if="!compareForm.keyMappings.length" class="empty-tip">尚未配置主键，点击「自动匹配主键」或手动添加</div>
        <div v-for="(mapping, index) in compareForm.keyMappings" :key="'k' + index" class="mapping-row">
          <n-select :value="mapping.source" :options="sourceColumnOptions" placeholder="源表主键列" filterable tag @update:value="(value) => setMapping('keyMappings', index, 'source', value)" />
          <span class="mapping-arrow">→</span>
          <n-select :value="mapping.target" :options="targetColumnOptions" placeholder="目标表主键列" filterable tag @update:value="(value) => setMapping('keyMappings', index, 'target', value)" />
          <n-button size="small" quaternary type="error" @click="removeMapping('keyMappings', index)">移除</n-button>
        </div>
        <div class="section-actions">
          <n-button size="small" @click="autoMatchKeys">自动匹配主键</n-button>
          <n-button size="small" @click="addMapping('keyMappings')">+ 添加主键</n-button>
        </div>
      </div>
      <div class="section">
        <div class="section-head">
          <div class="section-title">比较字段</div>
          <div class="section-tip">逐列一一对应比较；两端列名不同时手动指定映射</div>
        </div>
        <div v-if="!compareForm.fieldMappings.length" class="empty-tip">未配置比较字段，默认比较两表全部非主键列；点击「自动匹配比较字段」快速生成</div>
        <div v-for="(mapping, index) in compareForm.fieldMappings" :key="'f' + index" class="mapping-row">
          <n-select :value="mapping.source" :options="sourceColumnOptions" placeholder="源表字段" filterable tag @update:value="(value) => setMapping('fieldMappings', index, 'source', value)" />
          <span class="mapping-arrow">→</span>
          <n-select :value="mapping.target" :options="targetColumnOptions" placeholder="目标表字段" filterable tag @update:value="(value) => setMapping('fieldMappings', index, 'target', value)" />
          <n-button size="small" quaternary type="error" @click="removeMapping('fieldMappings', index)">移除</n-button>
        </div>
        <div class="section-actions">
          <n-button size="small" @click="autoMatchFields">自动匹配比较字段</n-button>
          <n-button size="small" @click="addMapping('fieldMappings')">+ 添加映射</n-button>
        </div>
      </div>
      <div class="section">
        <div class="section-head">
          <div class="section-title">排除字段</div>
          <div class="section-tip">这些字段不参与比较（如 updated_at、etl 标记列）</div>
        </div>
        <n-select v-model:value="compareForm.ignoreColumns" :options="ignoreColumnOptions" multiple filterable tag placeholder="选择要排除的字段（两端同名字段都会被排除）" />
      </div>
      <div class="hint-box">
        <span class="hint-title">提示</span>
        <p class="hint-body">主键与比较字段来自两端表结构的<b>实时读取</b>；主键用于对齐行，比较字段按位置一一对应比较，排除字段在两侧按列名过滤。</p>
      </div>
    </n-spin>
  </div>
</template>

<style scoped lang="scss">
@use "./shared.scss" as *;

// ===== Step 2: 比对键 =====
.empty-tip {
  padding: 10px 12px;
  margin-bottom: 10px;
  font-size: 12px;
  color: var(--text-muted);
  border: 1px dashed var(--border-strong);
  border-radius: var(--radius-ctl);
  background: var(--card);
}

.mapping-row {
  display: grid;
  grid-template-columns: 1fr 36px 1fr 64px;
  align-items: center;
  gap: 8px;
  margin-bottom: 10px;

  &:last-of-type {
    margin-bottom: 0;
  }
}

.mapping-arrow {
  text-align: center;
  font-size: 14px;
  color: var(--text-muted);
}

.section-actions {
  display: flex;
  gap: 8px;
  margin-top: 12px;
}

.hint-box {
  padding: 12px 16px;
  border: 1px solid var(--border);
  border-radius: var(--radius-ctl);
  background: var(--bg-subtle);
  margin-bottom: 4px;

  .hint-title {
    font-size: 12px;
    font-weight: 600;
    color: var(--text-secondary);
  }

  .hint-body {
    margin: 4px 0 0;
    font-size: 12px;
    line-height: 1.7;
    color: var(--text-secondary);

    b {
      color: var(--text);
      font-weight: 500;
    }
  }
}
</style>
