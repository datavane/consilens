<script setup>
import { computed } from "vue";
import { STATE_TAG_MAP } from "@/common/statusMapping";

const props = defineProps({
  status: { type: String, default: "" },
  label: { type: String, default: "" },
});

// 单一映射来源：各域的状态表集中在 statusMapping.js，这里只做大小写兼容
const meta = computed(
  () =>
    STATE_TAG_MAP[props.status] ||
    STATE_TAG_MAP[props.status && props.status.toUpperCase()] ||
    STATE_TAG_MAP[props.status && props.status.toLowerCase()] || {
      label: props.status || "-",
      type: "default",
    }
);
</script>

<template>
  <n-tag :type="meta.type" size="small" :bordered="false">
    <template #icon>
      <span class="state-dot" :class="meta.type" />
    </template>
    {{ label || meta.label }}
  </n-tag>
</template>

<style scoped lang="scss">
.state-dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: currentColor;
}
</style>
