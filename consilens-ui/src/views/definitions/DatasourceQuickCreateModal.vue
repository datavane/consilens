<script setup>
import { NButton, NInput, NSelect } from "naive-ui";
import DataSourceForm from "@/components/DataSourceForm.vue";

const props = defineProps({
  dsModal: { type: Object, required: true },
  typeOptions: { type: Array, default: () => [] },
  // 将内部表单实例回写到父级的 dsFormRef（校验/取值由父级动作调用）
  setFormRef: { type: Function, default: null },
});

defineEmits(["test", "create"]);

function bindFormRef(instance) {
  props.setFormRef?.(instance);
}
</script>

<template>
  <!-- 新建数据源弹框 -->
  <n-modal v-model:show="dsModal.show" preset="card" style="width: 640px" title="新建数据源">
    <div class="ds-form">
      <div class="ds-row-top">
        <div class="field">
          <label class="field-label">名称</label>
          <n-input v-model:value="dsModal.form.name" placeholder="例如：生产订单库" maxlength="128" />
        </div>
        <div class="field">
          <label class="field-label">类型</label>
          <n-select v-model:value="dsModal.form.type" :options="typeOptions" />
        </div>
      </div>
      <div class="ds-separator" />
      <data-source-form
        :ref="bindFormRef"
        v-model:model-value="dsModal.values"
        :fields="dsModal.fields"
      />
      <div v-if="dsModal.fields.length === 0" class="field text-muted" style="font-size: 12px">
        连接参数加载中…
      </div>
    </div>
    <template #footer>
      <div class="modal-footer">
        <div class="modal-footer-left">
          <n-button size="small" :loading="dsModal.testing" @click="$emit('test')">测试连接</n-button>
          <span v-if="dsModal.testResult" class="conn-result" :class="dsModal.testResult.ok ? 'ok' : 'fail'">
            <span class="conn-dot" />{{ dsModal.testResult.text }}
          </span>
        </div>
        <div class="modal-footer-right">
          <n-button size="small" @click="dsModal.show = false">取消</n-button>
          <n-button size="small" type="primary" :loading="dsModal.saving" @click="$emit('create')">创建</n-button>
        </div>
      </div>
    </template>
  </n-modal>
</template>

<style scoped lang="scss">
@use "./steps/shared.scss" as *;

.conn-result {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-top: 10px;
  font-size: 12px;

  &.ok {
    color: var(--success-text);

    .conn-dot {
      background: var(--success);
    }
  }

  &.fail {
    color: var(--danger);

    .conn-dot {
      background: var(--danger);
    }
  }
}

.conn-dot {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  flex-shrink: 0;
}

// ===== 数据源快捷创建弹框 =====
.ds-form {
  padding-top: 4px;
}

.ds-row-top {
  display: grid;
  grid-template-columns: 1fr 200px;
  gap: 16px;
  align-items: start;
}

.ds-separator {
  height: 1px;
  margin: 4px 0 16px;
  background: var(--border);
}

.modal-footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.modal-footer-left {
  display: flex;
  align-items: center;
  gap: 10px;
  min-width: 0;
}

.modal-footer-right {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-shrink: 0;
}
</style>
