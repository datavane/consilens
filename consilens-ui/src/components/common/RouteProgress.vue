<script setup>
import { onBeforeUnmount } from "vue";
import { useRouter } from "vue-router";
import { useLoadingBar } from "naive-ui";

// 路由切换顶栏进度条：必须在 n-loading-bar-provider 内使用
const router = useRouter();
const bar = useLoadingBar();

// 保留反注册函数：HMR / 组件重挂载时移除旧守卫，避免重复注册
const removeBeforeEach = router.beforeEach(() => {
  bar.start();
});
const removeAfterEach = router.afterEach(() => {
  bar.finish();
});
const removeOnError = router.onError(() => {
  bar.error();
});

onBeforeUnmount(() => {
  removeBeforeEach();
  removeAfterEach();
  removeOnError();
});
</script>

<template>
  <span style="display: none" />
</template>
