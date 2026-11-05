<script setup>
// 数据源连接参数动态表单：按后端返回的字段模板渲染 naive-ui 控件
// props.fields: [{field,title,type,placeholder,required,defaultValue,rows,options}]
// type: input | number | textarea | select
// 通过 v-model:model-value 双向绑定字段值（统一以字符串存取）；
// 通过 ref 调用 validateAndGetValues() / resetValues() / setValues() / getValues()。
import { ref, computed, watch } from "vue";
import { NForm, NFormItem, NInput, NInputNumber, NSelect } from "naive-ui";

const props = defineProps({
  fields: { type: Array, default: () => [] },
  modelValue: { type: Object, default: () => ({}) },
  // 编辑态：密码字段 placeholder 提示“留空则保持原密码”
  editing: { type: Boolean, default: false },
});
const emit = defineEmits(["update:modelValue"]);

const formRef = ref(null);

// 字段布局分组：按稳定字段名把连接参数排成两列，减少纵向堆叠
const KNOWN_GROUPS = [
  { key: "endpoint", members: ["host", "port"] },
  { key: "db", members: ["database", "schema", "sid"] },
  { key: "auth", members: ["username", "password"] },
  { key: "props", members: ["properties"] },
];

function groupFields(fields) {
  const byKey = {};
  for (const f of fields) byKey[f.field] = f;
  const groups = [];
  for (const g of KNOWN_GROUPS) {
    const members = g.members.map((m) => byKey[m]).filter(Boolean);
    if (members.length) groups.push(members);
  }
  // 模板新增的未知字段各自独占一行（保持兼容）
  const known = new Set(KNOWN_GROUPS.flatMap((g) => g.members));
  for (const f of fields) {
    if (!known.has(f.field)) groups.push([f]);
  }
  return groups;
}

const fieldGroups = computed(() => groupFields(props.fields));

function isPassword(field) {
  return (
    field.field === "password" ||
    (field.title || "").includes("密码") ||
    (field.title || "").includes("口令")
  );
}

function textValue(field) {
  const raw = props.modelValue[field.field];
  return raw === undefined || raw === null ? "" : raw;
}

function numValue(field) {
  const raw = props.modelValue[field.field];
  if (raw === undefined || raw === null || raw === "") return null;
  const n = Number(raw);
  return Number.isFinite(n) ? n : null;
}

function onInput(field, value) {
  emit("update:modelValue", { ...props.modelValue, [field.field]: value ?? "" });
}

function onNumInput(field, value) {
  onInput(field, value === null || value === undefined ? "" : String(value));
}

function passwordPlaceholder(field) {
  if (!isPassword(field)) return field.placeholder || "";
  return props.editing ? "留空则保持原密码" : field.placeholder || "";
}

// 校验规则：required 字段必填
const rules = computed(() => {
  const r = {};
  for (const f of props.fields) {
    if (f.required) {
      r[f.field] = [{ required: true, message: `请填写${f.title}`, trigger: ["blur", "input"] }];
    }
  }
  return r;
});

// fields 变化时（切换类型 / 首次加载）为缺失字段补充默认值，不覆盖已有值
watch(
  () => props.fields,
  (fields) => {
    const next = { ...props.modelValue };
    let changed = false;
    for (const f of fields) {
      const raw = next[f.field];
      if (raw === undefined || raw === null || raw === "") {
        next[f.field] = f.defaultValue !== undefined && f.defaultValue !== null ? String(f.defaultValue) : "";
        changed = true;
      }
    }
    if (changed) emit("update:modelValue", next);
  },
  { immediate: true, deep: true }
);

function clearValidation() {
  formRef.value?.restoreValidation();
}

// 收集当前值（不校验），字段值统一转字符串
function getValues() {
  const values = {};
  for (const f of props.fields) {
    const raw = props.modelValue[f.field];
    values[f.field] = raw === undefined || raw === null ? "" : String(raw);
  }
  return values;
}

// 校验必填字段，通过后返回 { valid: true, values }
async function validateAndGetValues() {
  try {
    await formRef.value?.validate();
  } catch {
    return { valid: false, values: null };
  }
  return { valid: true, values: getValues() };
}

// 重置为模板默认值（切换类型 / 新建时调用）
function resetValues() {
  const next = {};
  for (const f of props.fields) {
    next[f.field] = f.defaultValue !== undefined && f.defaultValue !== null ? String(f.defaultValue) : "";
  }
  emit("update:modelValue", next);
  clearValidation();
}

// 编辑回填：只覆盖有值的键；密码不传入时保持空（编辑态留空则后端保留原密码）
function setValues(obj) {
  const next = { ...props.modelValue };
  for (const f of props.fields) {
    const raw = obj?.[f.field];
    if (raw !== undefined && raw !== null && raw !== "") {
      next[f.field] = String(raw);
    } else if (next[f.field] === undefined || next[f.field] === null || next[f.field] === "") {
      next[f.field] = f.defaultValue !== undefined && f.defaultValue !== null ? String(f.defaultValue) : "";
    }
  }
  emit("update:modelValue", next);
  clearValidation();
}

defineExpose({ validateAndGetValues, getValues, resetValues, setValues });
</script>

<template>
  <n-form ref="formRef" :model="modelValue" :rules="rules" label-placement="top" class="ds-fields">
    <div v-for="(group, gi) in fieldGroups" :key="gi" class="ds-row" :class="{ 'ds-row-single': group.length === 1 }">
      <n-form-item
        v-for="field in group"
        :key="field.field"
        :label="field.title"
        :path="field.field"
        :show-require-mark="field.required"
        class="ds-col"
      >
        <n-input
          v-if="field.type === 'textarea'"
          :value="textValue(field)"
          type="textarea"
          :rows="field.rows || 3"
          :autosize="{ minRows: field.rows || 3, maxRows: 8 }"
          :placeholder="field.placeholder || ''"
          @update:value="onInput(field, $event)"
        />
        <n-input-number
          v-else-if="field.type === 'number'"
          :value="numValue(field)"
          :placeholder="field.placeholder || ''"
          :min="1"
          style="width: 100%"
          @update:value="onNumInput(field, $event)"
        />
        <n-select
          v-else-if="field.type === 'select'"
          :value="textValue(field)"
          :options="field.options || []"
          :placeholder="field.placeholder || ''"
          clearable
          @update:value="onInput(field, $event)"
        />
        <n-input
          v-else
          :value="textValue(field)"
          :type="isPassword(field) ? 'password' : 'text'"
          :show-password-on="isPassword(field) ? 'click' : undefined"
          :placeholder="passwordPlaceholder(field)"
          @update:value="onInput(field, $event)"
        />
      </n-form-item>
    </div>
  </n-form>
</template>

<style scoped lang="scss">
.ds-fields {
  :deep(.n-form-item) {
    margin-bottom: 14px;
  }

  :deep(.n-form-item-label) {
    font-size: 13px;
    font-weight: 500;
    color: var(--text);
  }

  :deep(.n-form-item-label-text) {
    font-size: 13px;
    font-weight: 500;
    color: var(--text);
  }
}

.ds-row {
  display: grid;
  grid-template-columns: 1fr 1fr;
  column-gap: 16px;
  align-items: start;

  &.ds-row-single {
    grid-template-columns: 1fr;
  }
}

.ds-col {
  min-width: 0;
}
</style>
