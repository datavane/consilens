<script setup>
// 把 naive-ui 的 message 实例注入 axios 拦截器：API 错误才能弹出全局提示。
// 必须在 n-message-provider 内部渲染。
import { useMessage } from "naive-ui";
import { setupMessage } from "@/api";

setupMessage(useMessage());
</script>

<template>
  <span class="message-bridge" aria-hidden="true" />
</template>

<style scoped>
.message-bridge {
  display: none;
}
</style>
