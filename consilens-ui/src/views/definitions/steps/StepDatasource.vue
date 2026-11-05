<script setup>
import { NButton, NInput, NSelect, NRadioGroup, NRadioButton } from "naive-ui";

defineProps({
  planForm: { type: Object, required: true },
  datasourceOptions: { type: Array, default: () => [] },
});

const emit = defineEmits(["open-ds-modal", "datasource-change", "database-change", "table-change"]);
</script>

<template>
  <!-- Step 1: 数据源 -->
  <div class="panel">
    <div class="panel-head">
      <h2 class="panel-title">对比目标</h2>
      <p class="panel-desc">描述本次比对的目的，用于生成配置</p>
    </div>
    <div class="field">
      <label class="field-label">目标描述</label>
      <n-input v-model:value="planForm.goal" placeholder="例如：生成源表与目标表的数据对比配置" />
    </div>

    <div class="side-grid">
      <div v-for="side in ['source', 'target']" :key="side" class="side-card">
        <div class="side-head">
          <span class="side-badge" :class="side">{{ side === "source" ? "源" : "目标" }}</span>
          <span class="side-name">{{ side === "source" ? "源端" : "目标端" }}</span>
          <span class="side-code">{{ side }}</span>
        </div>
        <div class="field">
          <label class="field-label">数据源</label>
          <div class="inline-row">
            <n-select
              v-model:value="planForm[side].datasourceId"
              :options="datasourceOptions"
              placeholder="选择已配置的数据源"
              class="flex-1"
              @update:value="emit('datasource-change', side)"
            />
            <n-button size="small" @click="emit('open-ds-modal', side)" title="新建数据源">+ 添加</n-button>
          </div>
        </div>
        <div class="field">
          <label class="field-label">数据范围</label>
          <n-radio-group v-model:value="planForm[side].mode" size="small">
            <n-radio-button value="table">表</n-radio-button>
            <n-radio-button value="sql">自定义 SQL</n-radio-button>
          </n-radio-group>
        </div>
        <template v-if="planForm[side].mode === 'table'">
          <div class="field">
            <label class="field-label">数据库</label>
            <n-select
              v-model:value="planForm[side].database"
              :options="planForm[side].databases.map((name) => ({ label: name, value: name }))"
              :loading="planForm[side].databasesLoading"
              :disabled="!planForm[side].datasourceId"
              placeholder="选择数据库"
              @update:value="emit('database-change', side)"
            />
          </div>
          <div class="field">
            <label class="field-label">表</label>
            <n-select
              v-model:value="planForm[side].table"
              :options="planForm[side].tables.map((name) => ({ label: name, value: name }))"
              :loading="planForm[side].tablesLoading"
              :disabled="!planForm[side].database"
              placeholder="选择表"
              @update:value="emit('table-change', side)"
            />
          </div>
        </template>
        <template v-else>
          <div class="field">
            <label class="field-label">数据库</label>
            <n-select
              v-model:value="planForm[side].database"
              :options="planForm[side].databases.map((name) => ({ label: name, value: name }))"
              :loading="planForm[side].databasesLoading"
              :disabled="!planForm[side].datasourceId"
              placeholder="选择数据库（用于连接 URL）"
              @update:value="emit('database-change', side)"
            />
          </div>
          <div class="field">
            <label class="field-label">自定义查询</label>
            <n-input
              v-model:value="planForm[side].query"
              type="textarea"
              :rows="3"
              placeholder="SELECT ... FROM db.table WHERE ..."
            />
          </div>
        </template>
      </div>
    </div>
  </div>
</template>

<style scoped lang="scss">
@use "./shared.scss" as *;

// ===== 源/目标双栏 =====
.side-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 12px;
  margin-bottom: 4px;
}

.side-card {
  padding: 16px;
  border: 1px solid var(--border);
  border-radius: var(--radius-ctl);
  background: var(--bg-subtle);
}

.side-head {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 14px;

  .side-badge {
    display: inline-flex;
    align-items: center;
    justify-content: center;
    min-width: 22px;
    height: 22px;
    padding: 0 6px;
    border-radius: var(--radius-badge);
    font-size: 11px;
    font-weight: 600;
    color: var(--on-primary);

    &.source {
      background: var(--primary-solid);
    }

    &.target {
      background: var(--success-solid);
    }
  }

  .side-name {
    font-size: 13px;
    font-weight: 600;
    color: var(--text);
  }

  .side-code {
    margin-left: auto;
    font-size: 12px;
    color: var(--text-muted);
    font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
  }
}

.side-card .field {
  margin-bottom: 12px;
}

// ===== 响应式 =====
@media (max-width: 768px) {
  .side-grid {
    grid-template-columns: 1fr;
  }
}
</style>
