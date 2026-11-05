import test from "node:test";
import assert from "node:assert/strict";
import { clampScore, formatScore } from "./scoreDisplay.js";

test("负数与超 100 被夹到 [0,100]", () => {
  assert.equal(clampScore(-15), 0);
  assert.equal(clampScore(135), 100);
  assert.equal(clampScore(72.5), 72.5);
});

test("null / undefined / NaN 视为无数据", () => {
  assert.equal(clampScore(null), null);
  assert.equal(clampScore(undefined), null);
  assert.equal(clampScore(Number.NaN), null);
  assert.equal(formatScore(Number.NaN), "—");
});

test("展示保留一位小数，负分不会显示负号", () => {
  assert.equal(formatScore(-3.2), "0.0");
  assert.equal(formatScore(99.94), "99.9");
  assert.equal(formatScore(null), "—");
});
