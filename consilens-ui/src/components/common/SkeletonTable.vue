<script setup>
defineProps({
  rows: { type: Number, default: 8 },
});

// 每行三段的固定宽度（%）：稳定渲染，避免伪随机宽度造成视觉抖动
const WIDTHS = [
  [38, 24, 18],
  [26, 32, 14],
  [44, 20, 12],
  [30, 26, 20],
  [36, 22, 16],
  [24, 34, 14],
  [40, 18, 20],
  [28, 30, 16],
];

function barWidth(rowIndex, segment) {
  return WIDTHS[(rowIndex - 1) % WIDTHS.length][segment] + "%";
}
</script>

<template>
  <div class="skeleton-table">
    <div v-for="i in rows" :key="i" class="sk-row">
      <div class="sk-bar" :style="{ width: barWidth(i, 0) }" />
      <div class="sk-bar" :style="{ width: barWidth(i, 1) }" />
      <div class="sk-bar" :style="{ width: barWidth(i, 2) }" />
    </div>
  </div>
</template>

<style scoped lang="scss">
.skeleton-table {
  display: flex;
  flex-direction: column;
  justify-content: center;
  gap: 14px;
  padding: 16px 4px;
}

.sk-row {
  display: flex;
  gap: 16px;
}

.sk-bar {
  height: 12px;
  border-radius: 4px;
  background: linear-gradient(90deg, var(--border) 25%, var(--bg-subtle) 50%, var(--border) 75%);
  background-size: 200% 100%;
  animation: sk 1.2s infinite;
}

@keyframes sk {
  0% {
    background-position: 200% 0;
  }

  100% {
    background-position: -200% 0;
  }
}
</style>
