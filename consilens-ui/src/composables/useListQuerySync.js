import { computed } from "vue";
import { useRoute, useRouter } from "vue-router";

/**
 * 列表页筛选 / 分页 ↔ URL query 双向同步（可分享 / 刷新保留）：
 * - 初始化：按 fields 声明从 query 恢复筛选值（query 中缺省时保持 filters 的初始默认值），
 *   并从 query 恢复页码（缺省为第 1 页）
 * - syncToUrl()：加载前写回（空值不写入；页码 > 1 才写入）
 * - filtersActive：任一筛选非空
 *
 * fields: { 筛选字段: { queryKey?, parse?, serialize? } }
 * - queryKey：URL 参数名（缺省与字段名一致）
 * - parse：query 值 → 筛选值（缺省原样取用，如 ?enabled=true 这类三态转换在此实现）
 * - serialize：筛选值 → query 字符串（缺省 String，如毫秒时间戳用 String(filters.x)）
 */
export function useListQuerySync(filters, pagination, fields) {
  const route = useRoute();
  const router = useRouter();

  for (const [key, spec = {}] of Object.entries(fields)) {
    const raw = route.query[spec.queryKey || key];
    if (raw === undefined) continue;
    // 重复 query（?keyword=a&keyword=b）对字符串字段只取第一个；
    // 数组字段（如 status）保留数组交给 parse 处理。
    const value = Array.isArray(raw) && !Array.isArray(filters[key]) ? raw[0] : raw;
    filters[key] = spec.parse ? spec.parse(value) : value;
  }
  // query.page 可被手工编辑：负数/小数/超大值会让后端分页校验直接 400，
  // 非正安全整数一律回到第 1 页。
  const page = Number(route.query.page);
  pagination.page = Number.isSafeInteger(page) && page > 0 ? page : 1;

  const isEmpty = (value) =>
    value === null || value === undefined || value === ""
    || (Array.isArray(value) && value.length === 0);

  const filtersActive = computed(() => Object.values(filters).some((value) => !isEmpty(value)));

  function syncToUrl() {
    const query = {};
    for (const [key, spec = {}] of Object.entries(fields)) {
      const value = filters[key];
      if (isEmpty(value)) continue;
      if (Array.isArray(value) && !spec.serialize) {
        query[spec.queryKey || key] = value.map(String);
      } else {
        query[spec.queryKey || key] = spec.serialize ? spec.serialize(value) : String(value);
      }
    }
    if (pagination.page > 1) query.page = String(pagination.page);
    // 数组值必须原样交给 router（写成 "A,B" 会让刷新后的 parse 只还原成一个元素）；
    // 单元素数组也保留数组形态，避免"选 1 个 → 刷新变成字符串"的不一致。
    // 值与 query 一致时跳过 replace：避免 onMounted 的首次加载也产生一次路由替换
    // （会触发路由 watch 连锁，并在浏览器历史里留下无意义记录）
    const current = route.query;
    const sameKeys = Object.keys(query).length === Object.keys(current).length;
    if (sameKeys && Object.keys(query).every((key) => normalizeQueryValue(current[key]) === normalizeQueryValue(query[key]))) {
      return;
    }
    router.replace({ query });
  }

  function normalizeQueryValue(value) {
    return Array.isArray(value) ? value.map(String).join(",") : String(value);
  }

  return { filtersActive, syncToUrl };
}
