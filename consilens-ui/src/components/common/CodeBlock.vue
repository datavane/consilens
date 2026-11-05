<script setup>
import { useMessage } from "naive-ui";

const props = defineProps({
  content: { type: String, default: "" },
  label: { type: String, default: "" },
  maxHeight: { type: Number, default: 360 },
});

const message = useMessage();

async function copyAll() {
  if (!navigator.clipboard) {
    message.error("当前环境不支持剪贴板，复制失败");
    return;
  }
  try {
    await navigator.clipboard.writeText(props.content);
    message.success("已复制");
  } catch {
    // 非安全上下文 / 权限被拒时 writeText 会抛错
    message.error("复制失败，请手动选择复制");
  }
}
</script>

<template>
  <div class="code-block">
    <div class="code-head">
      <span class="code-label">{{ label }}</span>
      <div class="code-actions">
        <button type="button" class="code-btn" @click="copyAll">复制</button>
      </div>
    </div>
    <pre class="code-body" :style="{ maxHeight: maxHeight + 'px' }">{{ content }}</pre>
  </div>
</template>

<style scoped lang="scss">
.code-block {
  border: 1px solid var(--code-border);
  border-radius: 8px;
  overflow: hidden;
  background: var(--code-bg);
}

.code-head {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 7px 14px;
  background: var(--code-chrome);
  border-bottom: 1px solid var(--code-border);
}

.code-label {
  font-size: 12px;
  font-weight: 500;
  color: var(--code-text);
}

.code-actions {
  margin-left: auto;
  display: flex;
  gap: 4px;
}

.code-btn {
  display: inline-flex;
  align-items: center;
  min-height: 24px;
  padding: 2px 8px;
  font-size: 11px;
  color: var(--code-muted);
  background: transparent;
  border: 1px solid transparent;
  border-radius: 5px;
  cursor: pointer;

  &:hover {
    color: var(--code-text);
    border-color: var(--code-border);
  }
}

.code-body {
  margin: 0;
  padding: 14px;
  overflow: auto;
  font-family: "JetBrains Mono", ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace;
  font-size: 12px;
  line-height: 1.6;
  color: var(--code-text);
  white-space: pre;
}
</style>
