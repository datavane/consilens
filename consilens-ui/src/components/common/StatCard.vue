<script setup>
import { NIcon } from "naive-ui";

defineProps({
  label: { type: String, required: true },
  value: { type: [String, Number], default: "-" },
  color: { type: String, default: "var(--text-primary)" },
  extra: { type: String, default: "" },
  clickable: { type: Boolean, default: false },
  // 右上角彩色图标（对齐参考设计的统计卡）
  icon: { type: [Object, Function], default: null },
  iconColor: { type: String, default: "var(--primary)" },
});

defineEmits(["click"]);
</script>

<template>
  <div class="stat-card" :class="{ clickable }" @click="$emit('click')">
    <div class="stat-top">
      <span class="stat-label">{{ label }}</span>
      <n-icon v-if="icon" :size="19" :color="iconColor" class="stat-icon">
        <component :is="icon" />
      </n-icon>
    </div>
    <div class="stat-value" :style="{ color }">{{ value }}</div>
    <div v-if="extra" class="stat-extra">{{ extra }}</div>
  </div>
</template>

<style scoped lang="scss">
.stat-card {
  background: var(--bg-surface);
  border: 1px solid var(--border);
  border-radius: var(--radius-card);
  padding: 15px 18px 16px;

  &.clickable {
    cursor: pointer;
    transition: border-color 0.15s ease;

    &:hover {
      border-color: var(--primary-border);
    }
  }
}

.stat-top {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  min-height: 20px;
}

.stat-label {
  font-size: 13px;
  font-weight: 500;
  color: var(--text-muted);
}

.stat-icon {
  flex-shrink: 0;
}

.stat-value {
  margin-top: 6px;
  font-size: 28px;
  font-weight: 600;
  line-height: 1.2;
  letter-spacing: -0.02em;
  font-variant-numeric: tabular-nums;
}

.stat-extra {
  margin-top: 4px;
  font-size: 12px;
  color: var(--text-muted);
}
</style>
