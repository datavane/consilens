<script setup>
import { useMessage } from "naive-ui";

defineProps({
  items: { type: Array, required: true }, // [{ label, value, copyable?, mono?, strong? }]
  columns: { type: Number, default: 1 },
});

const message = useMessage();

function copyText(text) {
  navigator.clipboard
    ?.writeText(String(text))
    .then(() => message.success("已复制"))
    .catch(() => message.error("复制失败"));
}
</script>

<template>
  <div class="kv-list" :style="{ gridTemplateColumns: `repeat(${columns}, 1fr)` }">
    <div v-for="(item, i) in items" :key="i" class="kv-item">
      <div class="kv-label text-secondary">{{ item.label }}</div>
      <div class="kv-value" :class="{ mono: item.mono }">
        <span
          v-if="
            item.value === null ||
            item.value === undefined ||
            item.value === ''
          "
          >-</span
        >
        <template v-else>
          <n-text :strong="!!item.strong">{{ item.value }}</n-text>
          <n-button
            v-if="item.copyable"
            text
            size="tiny"
            class="ml-8"
            @click="copyText(item.value)"
          >
            复制
          </n-button>
        </template>
      </div>
    </div>
  </div>
</template>

<style scoped lang="scss">
.kv-list {
  display: grid;
  gap: 14px 24px;
}

.kv-label {
  font-size: 12px;
  margin-bottom: 4px;
}

.kv-value {
  font-size: 13px;
  color: var(--text);
  word-break: break-all;
  display: flex;
  align-items: center;
}
</style>
