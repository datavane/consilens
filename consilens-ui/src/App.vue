<script setup>
import { darkTheme, zhCN, dateZhCN } from "naive-ui";
import { useAppStore } from "@/store";
import { naiveTheme } from "@/common/theme";
import RouteProgress from "@/components/common/RouteProgress.vue";
import MessageBridge from "@/components/common/MessageBridge.vue";

const appStore = useAppStore();
</script>

<template>
  <n-config-provider
    :theme="appStore.theme === 'dark' ? darkTheme : null"
    :theme-overrides="naiveTheme()"
    :locale="zhCN"
    :date-locale="dateZhCN"
  >
    <n-loading-bar-provider>
      <n-message-provider>
        <message-bridge />
        <n-dialog-provider>
          <route-progress />
          <router-view />
        </n-dialog-provider>
      </n-message-provider>
    </n-loading-bar-provider>
  </n-config-provider>
</template>
