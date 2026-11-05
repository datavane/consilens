<script setup>
import { computed } from "vue";
import { formatTime } from "@/utils/format";

const props = defineProps({
  value: { type: [String, Number], default: "" },
});

// 统一解析为毫秒时间戳；解析失败返回 null（渲染 "—"）
function parseTs(value) {
  if (!value) return null;
  const s = String(value);
  // 纯数字时间戳：秒级（10 位）补成毫秒；new Date("1791953200000") 会得到 Invalid Date
  if (/^\d{10}$/.test(s)) return Number(s) * 1000;
  if (/^\d{11,19}$/.test(s)) return Number(s);
  // ISO 等字符串交给 Date 解析：不截断（截断会丢掉末尾 Z，被当成本地时间）
  const t = new Date(s).getTime();
  return Number.isNaN(t) ? null : t;
}

const absolute = computed(() => {
  const t = parseTs(props.value);
  if (!t) return "—";
  return formatTime(t);
});

const relative = computed(() => {
  const t = parseTs(props.value);
  if (!t) return "—";
  const diff = Date.now() - t;
  if (diff < 60000) return "刚刚";
  if (diff < 3600000) return Math.floor(diff / 60000) + " 分钟前";
  if (diff < 86400000) return Math.floor(diff / 3600000) + " 小时前";
  if (diff < 86400000 * 30) return Math.floor(diff / 86400000) + " 天前";
  return absolute.value.slice(0, 10);
});
</script>

<template>
  <span class="time-text num" :title="absolute">{{ relative }}</span>
</template>

<style scoped lang="scss">
.time-text {
  white-space: nowrap;
}
</style>
