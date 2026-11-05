// 页面功能 E2E 测试配置。
// 运行方式：bash devlop/scripts/e2e/run.sh（内部开发环境；会先起专用后端与 vite）。
// 不直接 `npx playwright test`：后端/vite 由 run.sh 统一编排，避免端口残留。
import { defineConfig } from "@playwright/test";

export default defineConfig({
  testDir: "./e2e/tests",
  timeout: 90_000,
  expect: { timeout: 10_000 },
  // 用例间有数据依赖（向导产生的实例被 instances 用例消费），串行执行
  fullyParallel: false,
  workers: 1,
  retries: 1,
  reporter: [["list"], ["html", { outputFolder: "e2e/report", open: "never" }]],
  use: {
    baseURL: "http://localhost:5174",
    trace: "retain-on-failure",
    screenshot: "only-on-failure",
    actionTimeout: 15_000,
    navigationTimeout: 20_000,
  },
  outputDir: "e2e/artifacts",
  globalSetup: "./e2e/fixtures/global-setup.js",
  globalTeardown: "./e2e/fixtures/global-teardown.js",
});
