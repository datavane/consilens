<script setup>
import { NInput, NCheckbox } from "naive-ui";
import CodeBlock from "@/components/common/CodeBlock.vue";

defineProps({
  defForm: { type: Object, required: true },
  summary: { type: Array, default: () => [] },
  configDisplay: { type: String, default: "" },
});
</script>

<template>
  <!-- Step 4: 保存定义 -->
  <div class="panel">
    <div class="panel-head">
      <h2 class="panel-title">保存定义</h2>
      <p class="panel-desc">核对配置，保存为可复用的任务定义</p>
    </div>
    <div class="field">
      <label class="field-label">定义名称 <span class="required">*</span></label>
      <n-input v-model:value="defForm.name" placeholder="例如：订单表每日比对" maxlength="128" />
    </div>
    <div class="field">
      <label class="field-label">描述</label>
      <n-input v-model:value="defForm.description" placeholder="可选，说明该定义用途" maxlength="512" />
    </div>
    <div class="field">
      <n-checkbox v-model:checked="defForm.runNow">保存后立即运行一次</n-checkbox>
    </div>
    <div class="summary-list">
      <div v-for="item in summary" :key="item.label" class="summary-row">
        <span class="summary-label">{{ item.label }}</span>
        <span class="summary-value">{{ item.value }}</span>
      </div>
    </div>
    <CodeBlock :content="configDisplay" label="运行配置（保存到定义）" :max-height="320" />
  </div>
</template>

<style scoped lang="scss">
@use "./shared.scss" as *;

// ===== 确认页 =====
.summary-list {
  margin-bottom: 16px;
  border: 1px solid var(--border);
  border-radius: var(--radius-ctl);
  overflow: hidden;
}

.summary-row {
  display: flex;
  align-items: baseline;
  padding: 10px 16px;
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
</style>
