<script setup>
import { NIcon } from "naive-ui";
import { CheckmarkCircleOutline } from "@vicons/ionicons5";

defineProps({
  steps: { type: Array, default: () => [] },
  currentStep: { type: Number, default: 1 },
});

const emit = defineEmits(["step-click"]);
</script>

<template>
  <!-- 步骤条 -->
  <div class="stepper">
    <template v-for="(step, index) in steps" :key="step.title">
      <div v-if="index > 0" class="stepper-line" :class="{ filled: currentStep > index }" />
      <div
        class="stepper-item"
        :class="{ active: currentStep === index + 1, done: currentStep > index + 1 }"
        @click="emit('step-click', index)"
      >
        <div class="stepper-dot">
          <n-icon v-if="currentStep > index + 1" size="12"><CheckmarkCircleOutline /></n-icon>
          <span v-else>{{ index + 1 }}</span>
        </div>
        <div class="stepper-text">
          <div class="stepper-title">{{ step.title }}</div>
          <div class="stepper-desc">{{ step.desc }}</div>
        </div>
      </div>
    </template>
  </div>
</template>

<style scoped lang="scss">
// ===== 步骤条 =====
.stepper {
  flex-shrink: 0;
  display: flex;
  align-items: flex-start;
  padding: 16px 20px;
  margin-top: 12px;
  background: var(--card);
  border: 1px solid var(--border);
  border-radius: var(--radius-card);
}

.stepper-item {
  display: flex;
  align-items: center;
  gap: 10px;
  flex: 1;
  min-width: 0;
  cursor: default;
}

.stepper-line {
  flex: 1;
  height: 2px;
  margin: 0 6px;
  align-self: center;
  background: var(--border);
  border-radius: 1px;
  transition: background 0.2s ease;

  &.filled {
    background: var(--primary);
  }
}

.stepper-dot {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 26px;
  height: 26px;
  flex-shrink: 0;
  border-radius: 50%;
  font-size: 12.5px;
  font-weight: 500;
  color: var(--text-muted);
  background: var(--bg-subtle);
  border: 1px solid var(--border);

  .stepper-item.active & {
    color: var(--on-primary);
    background: var(--primary-solid);
    border-color: var(--primary-solid);
  }

  .stepper-item.done & {
    color: var(--primary-text);
    border-color: var(--primary);
    background: var(--card);
  }
}

.stepper-text {
  min-width: 0;
}

.stepper-title {
  font-size: 13px;
  font-weight: 500;
  color: var(--text-muted);
  white-space: nowrap;

  .stepper-item.active & {
    color: var(--text);
    font-weight: 600;
  }
}

.stepper-desc {
  margin-top: 2px;
  font-size: 12px;
  color: var(--text-muted);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

// ===== 响应式 =====
@media (max-width: 768px) {
  .stepper-desc {
    display: none;
  }
}
</style>
