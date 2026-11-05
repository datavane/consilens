<script setup>
import { computed } from "vue";
import { DIFF_SCORE_STATUS_MAP, statusTypeColor } from "@/common/statusMapping";
import { formatNumber, formatRatio } from "@/utils/format";

const props = defineProps({
  // TaskSummaryDto.diffSummary：{differenceCount, differencePercentage, status} | null
  summary: { type: Object, default: null },
});

const meta = computed(() => {
  if (!props.summary) return DIFF_SCORE_STATUS_MAP.NONE;
  return DIFF_SCORE_STATUS_MAP[props.summary.status] || DIFF_SCORE_STATUS_MAP.NONE;
});

const dotColor = computed(() => statusTypeColor(meta.value.type));

const text = computed(() => {
  if (!props.summary || props.summary.differenceCount === null || props.summary.differenceCount === undefined) {
    return "暂无差异数据";
  }
  const count = `${formatNumber(props.summary.differenceCount)} 条差异`;
  const pct = formatRatio(props.summary.differencePercentage);
  return pct === "-" ? count : `${count} · ${pct}`;
});
</script>

<template>
  <span class="diff-badge" :title="meta.label">
    <span class="dot" :style="{ background: dotColor }" />
    <span class="text">{{ text }}</span>
  </span>
</template>

<style scoped lang="scss">
.diff-badge {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: 12px;
  color: var(--text-secondary);
  white-space: nowrap;
}

.dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  flex-shrink: 0;
}

.text {
  font-variant-numeric: tabular-nums;
}
</style>
