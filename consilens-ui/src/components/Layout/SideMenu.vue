<script setup>
import { h, computed } from "vue";
import { useRoute, useRouter } from "vue-router";
import { NIcon } from "naive-ui";
import {
  GridOutline,
  PlayCircleOutline,
  PulseOutline,
  ServerOutline,
  LinkOutline,
  AlbumsOutline,
} from "@vicons/ionicons5";
import { menuRoutes, menuSections } from "@/router/routes";

function renderIcon(icon) {
  return () => h(NIcon, null, { default: () => h(icon) });
}

// 路由 meta.icon 的字符串名 → 图标组件（新增页面只需在 routes.js 的 meta 里补 title/icon/section）
const ICONS = {
  "grid-outline": GridOutline,
  "play-circle-outline": PlayCircleOutline,
  "pulse-outline": PulseOutline,
  "albums-outline": AlbumsOutline,
  "link-outline": LinkOutline,
  "server-outline": ServerOutline,
};

// 全站菜单唯一来源：带 meta.title 且未标记 hidden 的顶层路由（参数路由 / 子路由不进菜单）
const MENU = menuRoutes.filter(
  (item) => item.meta?.title && !item.meta.hidden && !item.path.includes(":")
);

// 分组渲染：标题与组顺序来自 routes.js 的 menuSections，组内顺序即路由声明顺序；
// 空分组（该段路由都被隐藏）直接丢掉，不留光秃秃的标题
const menuOptions = computed(() =>
  menuSections
    .map((section) => ({
      type: "group",
      key: section.key,
      label: section.label,
      children: MENU.filter((item) => item.meta.section === section.key).map((item) => ({
        label: item.meta.title,
        key: item.path,
        icon: ICONS[item.meta.icon] ? renderIcon(ICONS[item.meta.icon]) : undefined,
      })),
    }))
    .filter((group) => group.children.length)
);

const route = useRoute();
const router = useRouter();

// 详情页/向导页（/definitions/:id、/definitions/new、/instances/:id 等）时保持父菜单高亮，
// 避免进入子页面后侧栏没有任何选中项、用户失去位置感
const activeKey = computed(() => {
  const match = MENU.find(
    (item) => route.path === item.path || route.path.startsWith(item.path + "/")
  );
  return match ? match.path : route.path;
});

function handleSelect(key) {
  router.push(key);
}
</script>

<template>
  <div class="side-menu-wrap">
    <n-menu
      class="side-menu"
      :options="menuOptions"
      :value="activeKey"
      @update:value="handleSelect"
    />
  </div>
</template>

<style scoped lang="scss">
.side-menu-wrap {
  display: flex;
  flex-direction: column;
  height: 100%;
}

.side-menu {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
}
</style>
