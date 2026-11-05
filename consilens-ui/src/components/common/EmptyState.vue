<script setup>
defineProps({
  title: { type: String, default: "暂无数据" },
  description: { type: String, default: "" },
  action: { type: String, default: "" },
});

defineEmits(["action"]);
</script>

<template>
  <div class="empty-state">
    <div class="empty-glyph">◍</div>
    <div class="empty-title">{{ title }}</div>
    <div v-if="description" class="empty-desc">{{ description }}</div>
    <n-button v-if="action" size="small" class="mt-8" @click="$emit('action')">
      {{ action }}
    </n-button>
  </div>
</template>

<style scoped lang="scss">
.empty-state {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  padding: 48px 16px;
  gap: 6px;
}

.empty-glyph {
  font-size: 26px;
  line-height: 1;
  color: var(--border-strong);
}

.empty-title {
  font-size: 13px;
  color: var(--text-secondary);
}

.empty-desc {
  font-size: 12.5px;
  color: var(--text-muted);
  max-width: 420px;
  text-align: center;
}
</style>
