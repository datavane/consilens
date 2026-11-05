<script setup>
import { computed } from "vue";
import { useMessage } from "naive-ui";

const props = defineProps({
  value: { type: String, default: "" },
  // 超过该长度截断显示，悬停 title 展示全量，点击复制全量
  length: { type: Number, default: 18 },
});

const message = useMessage();

const full = computed(() => props.value || "");
const short = computed(() =>
  full.value.length > props.length ? full.value.slice(0, props.length) + "…" : full.value
);

async function copy() {
  if (!navigator.clipboard) {
    message.error("当前环境不支持剪贴板，复制失败");
    return;
  }
  try {
    await navigator.clipboard.writeText(full.value);
    message.success("已复制 " + full.value);
  } catch {
    // 非安全上下文 / 权限被拒时 writeText 会抛错
    message.error("复制失败，请手动选择复制");
  }
}
</script>

<template>
  <span class="copyable" :title="'点击复制：' + full" @click.stop="copy">{{ short }}</span>
</template>

<style scoped lang="scss">
.copyable {
  font-family: "JetBrains Mono", ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace;
  font-variant-numeric: tabular-nums;
  color: var(--primary-text);
  cursor: pointer;
  white-space: nowrap;

  &:hover {
    text-decoration: underline;
    text-underline-offset: 2px;
  }
}
</style>
