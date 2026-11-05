<script setup>
import { NButton, NInput, NInputNumber, NSelect, NSwitch, NRadioGroup, NRadioButton } from "naive-ui";
import { generateSerialNo } from "@/utils/format";

const props = defineProps({
  advForm: { type: Object, required: true },
  runForm: { type: Object, required: true },
  platformForm: { type: Object, required: true },
});

function regenerateSerialNo() {
  props.runForm.serialNo = generateSerialNo();
}
</script>

<template>
  <!-- Step 3: 高级选项 -->
  <div class="panel">
    <div class="panel-head">
      <h2 class="panel-title">高级选项</h2>
      <p class="panel-desc">任务序号、超时与运行模式</p>
    </div>
    <div class="section">
      <div class="section-head">
        <div class="section-title">比对策略</div>
        <div class="section-tip">对齐 CLI strategy 配置，控制比对引擎行为</div>
      </div>
      <div class="adv-grid">
        <div class="field">
          <label class="field-label">比对模式</label>
          <n-select v-model:value="advForm.mode" :options="[
            { label: 'checksum（默认）', value: 'checksum' },
            { label: 'join', value: 'join' },
          ]" />
        </div>
        <div class="field">
          <label class="field-label">校验算法</label>
          <n-select v-model:value="advForm.algorithm" :options="[
            { label: 'concat（拼接后 MD5，默认）', value: 'concat' },
            { label: 'xor（XOR 聚合，顺序不敏感）', value: 'xor' },
          ]" />
        </div>
        <div class="field">
          <label class="field-label">分段因子</label>
          <n-input-number v-model:value="advForm.bisectionFactor" :min="1" :max="64" class="flex-1" />
        </div>
        <div class="field">
          <label class="field-label">分段阈值</label>
          <n-input-number v-model:value="advForm.bisectionThreshold" :min="100" :step="1000" class="flex-1" />
        </div>
        <div class="field">
          <label class="field-label">批大小</label>
          <n-input-number v-model:value="advForm.batchSize" :min="1" :step="100" class="flex-1" />
        </div>
        <div class="field">
          <label class="field-label">本地比对模式</label>
          <n-select v-model:value="advForm.localCompareMode" :options="[
            { label: 'full', value: 'full' },
            { label: 'auto', value: 'auto' },
          ]" />
        </div>
        <div class="field switch-field">
          <div class="switch-text">
            <span class="field-label">校验唯一键</span>
            <span class="field-desc">比对前验证主键唯一性</span>
          </div>
          <n-switch v-model:value="advForm.validateUniqueKeys" />
        </div>
        <div class="field switch-field">
          <div class="switch-text">
            <span class="field-label">允许可空键</span>
            <span class="field-desc">键列可空时仍参与比对，NULL 键按 IS NULL 语义处理</span>
          </div>
          <n-switch v-model:value="advForm.allowNullableKeys" />
        </div>
        <div class="field">
          <label class="field-label">最大差异数</label>
          <n-input-number v-model:value="advForm.maxDifferences" :min="1" :step="1000" class="flex-1" placeholder="不限制" />
        </div>
      </div>
    </div>
    <div class="section">
      <div class="section-head">
        <div class="section-title">归一化</div>
        <div class="section-tip">两侧按类型统一口径，未填写则使用引擎默认（decimal 4 位、时间按方言原样）</div>
      </div>
      <div class="adv-grid">
        <div class="field">
          <label class="field-label">小数精度</label>
          <n-input-number
            v-model:value="advForm.decimalPrecision"
            :min="0"
            :max="10"
            :precision="0"
            class="flex-1"
            placeholder="默认 4"
            clearable
          />
        </div>
        <div class="field">
          <label class="field-label">时区</label>
          <n-input v-model:value="advForm.timezone" placeholder="如 UTC / Asia/Shanghai" clearable />
        </div>
      </div>
    </div>
    <div class="field">
      <label class="field-label">任务序号</label>
      <div class="inline-row">
        <n-input v-model:value="runForm.serialNo" class="flex-1" size="small" />
        <n-button size="small" @click="regenerateSerialNo">重新生成</n-button>
      </div>
    </div>
    <div class="field">
      <label class="field-label">超时时间</label>
      <div class="inline-row">
        <n-input-number v-model:value="runForm.timeoutMs" :min="1000" :step="1000" class="flex-1" />
        <span class="unit-hint">毫秒（集群提交时生效；本地执行可在实例页取消）</span>
      </div>
    </div>
    <div class="field">
      <label class="field-label">执行平台</label>
      <n-radio-group v-model:value="platformForm.platform" size="small">
        <n-radio-button value="local">Local（Server 内）</n-radio-button>
        <n-radio-button value="yarn">YARN</n-radio-button>
        <n-radio-button value="kubernetes">Kubernetes</n-radio-button>
      </n-radio-group>
    </div>
    <template v-if="platformForm.platform === 'yarn'">
      <div class="field">
        <label class="field-label">Runtime Archive（HDFS）<span class="required">*</span></label>
        <n-input v-model:value="platformForm.yarn.archive" placeholder="hdfs:///apps/consilens/runtime.zip" />
      </div>
      <div class="adv-grid">
        <div class="field">
          <label class="field-label">队列</label>
          <n-input v-model:value="platformForm.yarn.queue" placeholder="default" />
        </div>
        <div class="field">
          <label class="field-label">AM 内存</label>
          <n-input v-model:value="platformForm.yarn.amMemory" placeholder="1g" />
        </div>
        <div class="field">
          <label class="field-label">AM vCores</label>
          <n-input-number v-model:value="platformForm.yarn.amVCores" :min="1" :precision="0" class="flex-1" />
        </div>
        <div class="field">
          <label class="field-label">最大重试次数</label>
          <n-input-number v-model:value="platformForm.yarn.maxAppAttempts" :min="1" :precision="0" class="flex-1" placeholder="默认 1" />
        </div>
      </div>
      <div class="field">
        <label class="field-label">Staging 目录</label>
        <n-input v-model:value="platformForm.yarn.stagingDir" placeholder="hdfs:///user/consilens/.staging" />
      </div>
      <div class="field">
        <label class="field-label">附加文件</label>
        <n-input v-model:value="platformForm.yarn.files" placeholder="多个文件以英文逗号分隔" />
      </div>
      <div class="field">
        <label class="field-label">附加 JAR</label>
        <n-input v-model:value="platformForm.yarn.jars" placeholder="多个 JAR 以英文逗号分隔" />
      </div>
      <div class="field">
        <label class="field-label">应用标签</label>
        <n-input v-model:value="platformForm.yarn.tags" placeholder="多个标签以英文逗号分隔" />
      </div>
    </template>
    <template v-if="platformForm.platform === 'kubernetes'">
      <div class="field">
        <label class="field-label">运行镜像<span class="required">*</span></label>
        <n-input v-model:value="platformForm.kubernetes.image" placeholder="registry.example/consilens-runtime:0.3.0" />
      </div>
      <div class="adv-grid">
        <div class="field">
          <label class="field-label">Namespace</label>
          <n-input v-model:value="platformForm.kubernetes.namespace" />
        </div>
        <div class="field">
          <label class="field-label">Job 名称</label>
          <n-input v-model:value="platformForm.kubernetes.jobName" placeholder="留空自动生成" />
        </div>
        <div class="field">
          <label class="field-label">内存</label>
          <n-input v-model:value="platformForm.kubernetes.memory" placeholder="1g" />
        </div>
        <div class="field">
          <label class="field-label">CPU 核数</label>
          <n-input v-model:value="platformForm.kubernetes.cpu" placeholder="0.5" />
        </div>
      </div>
      <div class="field">
        <label class="field-label">Service Account</label>
        <n-input v-model:value="platformForm.kubernetes.serviceAccount" placeholder="可选" />
      </div>
      <div class="field">
        <label class="field-label">镜像拉取 Secret</label>
        <n-input v-model:value="platformForm.kubernetes.imagePullSecrets" placeholder="多个 Secret 以英文逗号分隔" />
      </div>
      <div class="field">
        <label class="field-label">环境变量</label>
        <n-input v-model:value="platformForm.kubernetes.envsText" type="textarea" :autosize="{ minRows: 2, maxRows: 6 }" placeholder="每行一个，例如：TZ=Asia/Shanghai" />
      </div>
      <div class="field">
        <label class="field-label">数据库密码 Secret</label>
        <n-input v-model:value="platformForm.kubernetes.secretEnvText" type="textarea" :autosize="{ minRows: 2, maxRows: 6 }" placeholder="SOURCE_PASSWORD=compare-db/source-password&#10;TARGET_PASSWORD=compare-db/target-password" />
        <div class="field-desc">存在数据库密码时必须填写 SOURCE_PASSWORD 与 TARGET_PASSWORD；每行格式为 ENV_NAME=secretName/secretKey。</div>
      </div>
    </template>
    <div class="field switch-field">
      <div class="switch-text">
        <span class="field-label">试运行</span>
        <span class="field-desc">开启后仅验证执行链路，不产生运行结果</span>
      </div>
      <n-switch v-model:value="runForm.dryRun" />
    </div>
  </div>
</template>

<style scoped lang="scss">
@use "./shared.scss" as *;

// ===== Step3 比对策略 =====
.adv-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 0 16px;
}
</style>
