<script setup>
import { watch } from "vue";
import { useRoute } from "vue-router";
import { useAppStore } from "@/store";
import { NIcon } from "naive-ui";
import { SunnyOutline, MoonOutline } from "@vicons/ionicons5";
import SideMenu from "./SideMenu.vue";

const route = useRoute();
const appStore = useAppStore();

// 主题类同步到 <html>：naive-ui 的抽屉/弹窗默认 teleport 到 body，
// 挂在根元素上才能让全局 CSS 变量（--card/--border/...）在弹层内继承。
watch(
  () => appStore.theme,
  (theme) => {
    document.documentElement.classList.toggle("dark", theme === "dark");
    document.documentElement.classList.toggle("bright", theme !== "dark");
  },
  { immediate: true }
);
</script>

<template>
  <div class="app-shell" :class="[appStore.theme]">
    <!-- 40px 通栏顶栏：品牌 + 主题切换 -->
    <header class="topbar">
      <div class="brand">
        <span class="brand-mark">C</span>
        <span class="brand-name">Consilens</span>
      </div>
      <div class="topbar-actions">
        <n-tooltip placement="bottom" :delay="400">
          <template #trigger>
            <button class="icon-btn" @click="appStore.toggleTheme()">
              <n-icon :size="17">
                <component :is="appStore.theme === 'dark' ? SunnyOutline : MoonOutline" />
              </n-icon>
            </button>
          </template>
          {{ appStore.theme === "dark" ? "切换到亮色" : "切换到暗色" }}
        </n-tooltip>
      </div>
    </header>

    <div class="app-body">
      <!-- 220px 侧栏（与外壳同色，右侧 1px 分割线；宽度对齐 EmonLens） -->
      <aside class="sidebar">
        <SideMenu />
      </aside>
      <!-- 内容区：平面矩形，与顶栏/侧栏以 1px 分割线区隔 -->
      <main class="content-panel">
        <router-view v-slot="{ Component }">
          <!-- 直接用 CSS 入场动画，不用 mode="out-in"：后台/无头环境会暂停 rAF，
               Vue 过渡可能永远停在 leave 状态，导致新页面不挂载。 -->
          <!-- key 用 path 而非 fullPath：query（如 ?tab=）变化不重建页面 -->
          <component :is="Component" :key="route.path" />
        </router-view>
      </main>
    </div>
  </div>
</template>

<style scoped lang="scss">
.app-shell {
  display: flex;
  flex-direction: column;
  height: 100%;
  overflow: hidden;
  background: var(--shell-bg);
}

.topbar {
  flex: 0 0 40px;
  height: 40px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding: 0 12px 0 14px;
  border-bottom: 1px solid var(--shell-divider);
}

.brand {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
}

.brand-mark {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 22px;
  height: 22px;
  border-radius: 6px;
  background: var(--primary);
  color: var(--on-primary);
  font-size: 12px;
  font-weight: 700;
  flex-shrink: 0;
}

.brand-name {
  font-size: 15px;
  font-weight: 700;
  letter-spacing: 0.1px;
  color: var(--text);
  white-space: nowrap;
}

.topbar-actions {
  display: flex;
  align-items: center;
  gap: 4px;
}

.icon-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 26px;
  height: 26px;
  padding: 0;
  border: none;
  border-radius: var(--radius-ctl);
  background: transparent;
  color: var(--text-secondary);
  cursor: pointer;

  &:hover {
    background: var(--nav-hover-bg);
    color: var(--text-primary);
  }
}

.app-body {
  flex: 1;
  min-height: 0;
  display: flex;
}

.sidebar {
  flex: 0 0 220px;
  width: 220px;
  min-height: 0;
  overflow-y: auto;
  overflow-x: hidden;
  border-right: 1px solid var(--shell-divider);
}

.content-panel {
  flex: 1;
  min-width: 0;
  min-height: 0;
  display: flex;
  flex-direction: column;
  background: var(--panel-bg);
  overflow: hidden;
}
</style>
