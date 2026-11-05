import { createRouter, createWebHashHistory } from "vue-router";
import routes from "./routes";

const router = createRouter({
  history: createWebHashHistory(),
  routes,
});

router.afterEach((to) => {
  const title = to.meta?.title;
  document.title = title ? `${title} · Consilens` : "Consilens";
});

export default router;
