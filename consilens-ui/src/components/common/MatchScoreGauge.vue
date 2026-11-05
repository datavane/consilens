<script setup>
import { computed } from "vue";
import { DIFF_SCORE_STATUS_MAP, statusTypeColor } from "@/common/statusMapping";
import { clampScore, formatScore } from "@/common/scoreDisplay";

const props = defineProps({
  // 0..100；null 表示无数据
  score: { type: Number, default: null },
  status: { type: String, default: "NONE" },
  size: { type: Number, default: 148 },
});

const R = 56;
const CIRCUMFERENCE = 2 * Math.PI * R;

const meta = computed(() => DIFF_SCORE_STATUS_MAP[props.status] || DIFF_SCORE_STATUS_MAP.NONE);
const color = computed(() => statusTypeColor(meta.value.type));

const dashOffset = computed(() => {
  const value = clampScore(props.score) ?? 0;
  return CIRCUMFERENCE * (1 - value / 100);
});

const display = computed(() => formatScore(props.score));

// 字号随 size 等比缩放（基准：148 → 30px 分数 / 12px 标签），标签保底 11px 保证可读
const scoreFontSize = computed(() => `${Math.round(props.size * 0.2)}px`);
const labelFontSize = computed(() => `${Math.max(11, Math.round(props.size * 0.09))}px`);
</script>

<template>
  <div class="gauge" :style="{ width: `${size}px`, height: `${size}px` }">
    <svg :width="size" :height="size" viewBox="0 0 148 148">
      <circle
        cx="74" cy="74" :r="R" fill="none"
        stroke="var(--border)" stroke-width="10"
      />
      <circle
        cx="74" cy="74" :r="R" fill="none"
        :stroke="color" stroke-width="10" stroke-linecap="round"
        :stroke-dasharray="CIRCUMFERENCE"
        :stroke-dashoffset="dashOffset"
        transform="rotate(-90 74 74)"
        class="gauge-arc"
      />
    </svg>
    <div class="gauge-center">
      <div class="gauge-score" :style="{ color, fontSize: scoreFontSize }">{{ display }}</div>
      <div class="gauge-label" :style="{ fontSize: labelFontSize }">{{ meta.label }}</div>
    </div>
  </div>
</template>

<style scoped lang="scss">
.gauge {
  position: relative;
  margin: 0 auto;
}

.gauge-arc {
  transition: stroke-dashoffset 0.6s ease;
}

.gauge-center {
  position: absolute;
  inset: 0;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 2px;
}

.gauge-score {
  font-size: 30px;
  font-weight: 700;
  line-height: 1.1;
}

.gauge-label {
  font-size: 12px;
  color: var(--text-secondary);
}
</style>
