const Layout = () => import("@/components/Layout/index.vue");

// 侧栏分组：顺序即渲染顺序，条目按 meta.section 归组（对标 EmonLens 的分段式分组标题）
export const menuSections = [
  { key: "overview", label: "概览" },
  { key: "compare", label: "校验" },
  { key: "resources", label: "资源" },
];

export const menuRoutes = [
  {
    path: "/dashboard",
    name: "dashboard",
    component: () => import("@/views/dashboard/index.vue"),
    meta: { title: "工作台", icon: "grid-outline", section: "overview" },
  },
  {
    path: "/definitions",
    name: "definitions",
    component: () => import("@/views/definitions/index.vue"),
    meta: { title: "任务定义", icon: "play-circle-outline", section: "compare" },
  },
  {
    path: "/definitions/new",
    name: "definition-new",
    component: () => import("@/views/definitions/new.vue"),
    meta: { title: "新建定义", hidden: true },
  },
  {
    path: "/definitions/:definitionId",
    name: "definition-detail",
    component: () => import("@/views/definitions/detail.vue"),
    meta: { title: "定义详情", hidden: true },
  },
  {
    path: "/instances",
    name: "instances",
    component: () => import("@/views/instances/index.vue"),
    meta: { title: "运行实例", icon: "pulse-outline", section: "compare" },
  },
  {
    path: "/instances/:taskId",
    name: "instance-detail",
    component: () => import("@/views/instances/detail.vue"),
    meta: { title: "实例详情", hidden: true },
  },
  {
    path: "/artifacts",
    name: "artifacts",
    component: () => import("@/views/artifacts/index.vue"),
    meta: { title: "Artifact 中心", icon: "albums-outline", section: "compare", hidden: true },
  },
  {
    path: "/artifacts/:artifactId",
    name: "artifact-detail",
    component: () => import("@/views/artifacts/detail.vue"),
    meta: { title: "Artifact 详情", hidden: true },
  },
  {
    path: "/datasources",
    name: "datasources",
    component: () => import("@/views/datasources/index.vue"),
    meta: { title: "数据源", icon: "link-outline", section: "resources" },
  },
  {
    path: "/nodes",
    name: "nodes",
    component: () => import("@/views/nodes/index.vue"),
    meta: { title: "节点与集群", icon: "server-outline", section: "resources" },
  },
  {
    path: "/:pathMatch(.*)*",
    name: "not-found",
    component: () => import("@/views/notFound.vue"),
    meta: { title: "页面不存在", hidden: true },
  },
];

const routes = [
  {
    path: "/",
    component: Layout,
    redirect: "/dashboard",
    children: menuRoutes,
  },
];

export default routes;
