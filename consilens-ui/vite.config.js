import { defineConfig } from "vite";
import vue from "@vitejs/plugin-vue";
import * as path from "path";
import AutoImport from "unplugin-auto-import/vite";
import Components from "unplugin-vue-components/vite";
import { NaiveUiResolver } from "unplugin-vue-components/resolvers";

// https://vitejs.dev/config/
export default defineConfig({
  resolve: {
    alias: {
      "@": path.resolve(__dirname, "src"),
    },
  },
  plugins: [
    vue(),
    AutoImport({
      imports: [
        "vue",
        {
          "naive-ui": [
            "useDialog",
            "useMessage",
            "useNotification",
            "useLoadingBar",
          ],
        },
      ],
    }),
    Components({
      resolvers: [NaiveUiResolver()],
    }),
  ],
  css: {
    preprocessorOptions: {
      scss: {
        api: "modern-compiler",
      },
    },
  },
  server: {
    // 5173 被其他本地服务占用，consilens-ui 固定使用 5174
    port: 5174,
    strictPort: true,
    proxy: {
      "/v1": {
        // E2E 运行时（devlop/scripts/e2e/run.sh）用 E2E_BACKEND 指向专用后端；默认指向开发服务
        target: process.env.E2E_BACKEND || "http://localhost:16789",
        changeOrigin: true,
      },
    },
  },
  build: {
    chunkSizeWarningLimit: 1000,
    rollupOptions: {
      output: {
        manualChunks: {
          echarts: ["echarts"],
          vendor: ["vue", "vue-router", "pinia", "axios", "dayjs", "naive-ui"],
          icons: ["@vicons/ionicons5"],
        },
      },
    },
  },
});
