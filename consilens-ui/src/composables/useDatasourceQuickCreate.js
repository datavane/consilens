import { computed, nextTick, reactive, ref, watch } from "vue";
import {
  listDatasourceTypes,
  getDatasourceTypeConfig,
  createDatasource,
  testConnection,
} from "@/api/modules";
import { DEFAULT_DS_TYPE, FALLBACK_DS_FIELDS, collectDsParam, splitTestOptions } from "@/common/datasourceDefaults";

// ===== 数据源快捷创建弹框 =====
export function useDatasourceQuickCreate({
  planForm,
  datasources,
  loadDatasources,
  handleDatasourceChange,
  message,
}) {
  const dsModal = reactive({
    show: false,
    side: null,
    saving: false,
    testing: false,
    testResult: null,
    types: [],
    fields: [], // 当前类型的连接参数模板（来自后端）
    values: {}, // 模板字段值（字符串）
    form: {
      name: "",
      type: DEFAULT_DS_TYPE,
    },
  });

  // 动态表单实例（validateAndGetValues / setValues）
  const dsFormRef = ref(null);

  // 拉取类型模板：失败时使用兜底模板；序号防止快速切换类型时竞态覆盖
  let dsConfigSeq = 0;
  async function loadDsConfig(type) {
    const seq = ++dsConfigSeq;
    dsModal.fields = [];
    dsModal.values = {};
    let fields = FALLBACK_DS_FIELDS;
    try {
      const config = await getDatasourceTypeConfig(type);
      if (seq !== dsConfigSeq) return;
      fields = Array.isArray(config) && config.length ? config : FALLBACK_DS_FIELDS;
    } catch {
      if (seq !== dsConfigSeq) return;
    }
    dsModal.fields = fields;
    await nextTick();
    if (seq === dsConfigSeq) {
      dsFormRef.value?.setValues({});
    }
  }

  // 切换类型：重置模板与表单值
  watch(
    () => dsModal.form.type,
    (type) => {
      if (dsModal.show && type) loadDsConfig(type);
    }
  );

  async function openDsModal(side) {
    dsModal.side = side;
    dsModal.testResult = null;
    dsModal.form = { name: "", type: DEFAULT_DS_TYPE };
    if (!dsModal.types.length) {
      try {
        dsModal.types = (await listDatasourceTypes()) || [];
      } catch {
        dsModal.types = [];
      }
    }
    const defaultType = dsModal.types.find((t) => t.type === DEFAULT_DS_TYPE)?.type || dsModal.types[0]?.type || DEFAULT_DS_TYPE;
    dsModal.form.type = defaultType;
    dsModal.show = true;
    // type 未变化时 watch 不触发，这里显式加载模板
    loadDsConfig(defaultType);
  }

  const dsTypeOptions = computed(() =>
    dsModal.types.map((item) => ({ label: `${item.type}（默认端口 ${item.defaultPort}）`, value: item.type }))
  );

  async function handleDsTest() {
    const { valid, values } = (await dsFormRef.value?.validateAndGetValues()) || { valid: false, values: null };
    if (!valid) return;
    dsModal.testing = true;
    dsModal.testResult = null;
    try {
      // 仅当字段有值才放入；sid / schema / properties 放入 options
      const param = collectDsParam(values, dsModal.fields);
      const options = splitTestOptions(param);
      const result = await testConnection({
        type: dsModal.form.type,
        ...param,
        options,
      });
      dsModal.testResult = { ok: result.success, text: result.error || "连接成功" };
    } catch (error) {
      dsModal.testResult = { ok: false, text: error?.response?.data?.error || "连接失败" };
    } finally {
      dsModal.testing = false;
    }
  }

  async function handleDsCreate() {
    if (!dsModal.form.name.trim()) {
      message.warning("请填写名称");
      return;
    }
    const { valid, values } = (await dsFormRef.value?.validateAndGetValues()) || { valid: false, values: null };
    if (!valid) return;
    dsModal.saving = true;
    try {
      const created = await createDatasource({
        name: dsModal.form.name.trim(),
        type: dsModal.form.type,
        param: collectDsParam(values, dsModal.fields),
      });
      message.success("数据源已创建");
      dsModal.show = false;
      // 刷新下拉并自动选中新数据源
      await loadDatasources(true);
      if (dsModal.side) {
        planForm[dsModal.side].datasourceId = created.id;
        handleDatasourceChange(dsModal.side);
      }
    } catch (error) {
      message.error(error?.response?.data?.error || "创建失败");
    } finally {
      dsModal.saving = false;
    }
  }

  return { dsModal, dsFormRef, dsTypeOptions, openDsModal, handleDsTest, handleDsCreate };
}
