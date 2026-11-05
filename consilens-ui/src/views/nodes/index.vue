<script setup>
import { ref, onMounted } from "vue";
import { useMessage } from "naive-ui";
import { RefreshOutline } from "@vicons/ionicons5";
import { listNodes, getNode } from "@/api/modules";
import StateTag from "@/components/common/StateTag.vue";
import EmptyState from "@/components/common/EmptyState.vue";

const message = useMessage();

const loading = ref(false);
const loadError = ref(false);
const nodes = ref([]);
const refreshing = ref(false);

let nodesSeq = 0;
async function loadNodes(showRefresh = false) {
  const seq = ++nodesSeq;
  if (showRefresh) refreshing.value = true;
  else loading.value = true;
  loadError.value = false;
  try {
    const result = await listNodes();
    if (seq !== nodesSeq) return;
    nodes.value = result;
  } catch {
    if (seq !== nodesSeq) return;
    loadError.value = true;
    nodes.value = [];
  } finally {
    if (seq === nodesSeq) {
      loading.value = false;
      refreshing.value = false;
    }
  }
}

// ===== 详情 drawer =====
const detailVisible = ref(false);
const detailLoading = ref(false);
const detail = ref(null);

let detailSeq = 0;
async function openDetail(node) {
  const seq = ++detailSeq;
  detail.value = node;
  detailVisible.value = true;
  detailLoading.value = true;
  try {
    const result = await getNode(node.nodeKey);
    if (seq !== detailSeq) return;
    detail.value = result;
  } catch (e) {
    if (seq !== detailSeq) return;
    message.error(e.message);
  } finally {
    if (seq === detailSeq) {
      detailLoading.value = false;
    }
  }
}

// 内存占比的兜底基准（MB）：后端节点数据目前只返回 availableMemoryMb，没有总内存配置，
// 因此以 16GB 作为展示基准；若后端后续返回总内存字段，应优先使用后端配置值。
const DEFAULT_MEMORY_MB = 16384;

function memoryBar(availableMb) {
  if (availableMb === null || availableMb === undefined) return 0;
  return Math.min(100, Math.round((availableMb / DEFAULT_MEMORY_MB) * 100));
}

// 可用内存越少越告警；无数据（null/undefined）用中性色，不能当成"内存耗尽"
function memoryColor(availableMb) {
  if (availableMb === null || availableMb === undefined) return "var(--text-muted)";
  const pct = memoryBar(availableMb);
  if (pct < 15) return "var(--danger)";
  if (pct < 30) return "var(--warning)";
  return "var(--success)";
}

function loadBar(loadAverage) {
  if (loadAverage === null || loadAverage === undefined) return 0;
  return Math.min(100, Math.round(loadAverage * 10));
}

// 0 MB 是“内存已耗尽”的有效值，不能和缺失数据一样显示 "-"
function formatMemory(availableMb) {
  if (availableMb === null || availableMb === undefined) return "-";
  return Math.round(availableMb) + " MB";
}

onMounted(() => loadNodes());
</script>

<template>
  <div class="page-container nodes-page">
    <div class="page-header">
      <div>
        <div class="page-title">节点与集群</div>
        <div class="page-desc">已注册的执行节点与集群状态</div>
      </div>
      <div class="head-actions">
        <n-button size="small" :loading="refreshing" @click="loadNodes(true)">
          <template #icon>
            <n-icon><RefreshOutline /></n-icon>
          </template>
          刷新拓扑
        </n-button>
      </div>
    </div>

    <n-spin :show="loading">
      <n-grid v-if="nodes.length" :cols="3" :x-gap="12" :y-gap="12" responsive="screen" item-responsive class="mt-12">
        <n-grid-item v-for="node in nodes" :key="node.nodeKey" span="3 s:2 m:1">
          <n-card size="small" :bordered="true" class="node-card" @click="openDetail(node)">
            <div class="flex-bc mb-12">
              <n-tag :type="node.status === 'OFFLINE' ? 'default' : 'info'" size="small" :bordered="false" class="mono">{{ node.nodeKey }}</n-tag>
              <StateTag :status="node.status" />
            </div>
            <div class="node-meta mb-12">
              <span class="mono">{{ node.host }}:{{ node.port }}</span>
            </div>
            <n-statistic label="负载均值 (load average)" :value="node.loadAverage?.toFixed?.(2) ?? node.loadAverage ?? '-'" />
            <div class="mt-8">
              <n-progress
                type="line"
                :percentage="memoryBar(node.availableMemoryMb)"
                :show-indicator="false"
                :color="memoryColor(node.availableMemoryMb)"
                rail-color="var(--border)"
                height="6px"
              />
              <div class="text-muted" style="font-size: 11.5px; margin-top: 4px">
                可用内存 {{ formatMemory(node.availableMemoryMb) }}
              </div>
            </div>
          </n-card>
        </n-grid-item>
      </n-grid>

      <n-card v-else-if="!loading" size="small" :bordered="true" class="mt-12">
        <EmptyState
          :title="loadError ? '加载失败' : '暂无注册节点'"
          :description="loadError ? '网络或服务异常，请重试' : '节点注册后将在此展示负载与内存状态'"
          :action="loadError ? '重试' : ''"
          @action="loadNodes(true)"
        />
      </n-card>
    </n-spin>

    <n-drawer v-model:show="detailVisible" :width="480">
      <n-drawer-content :title="detail?.nodeKey || '节点详情'" closable>
        <n-spin :show="detailLoading">
          <n-descriptions v-if="detail" label-placement="left" :column="1" bordered size="small">
            <n-descriptions-item label="节点 Key">{{ detail.nodeKey }}</n-descriptions-item>
            <n-descriptions-item label="地址">{{ detail.host }}:{{ detail.port }}</n-descriptions-item>
            <n-descriptions-item label="状态">
              <StateTag :status="detail.status" />
            </n-descriptions-item>
            <n-descriptions-item label="负载均值">{{ detail.loadAverage?.toFixed?.(2) ?? detail.loadAverage ?? "-" }}</n-descriptions-item>
            <n-descriptions-item label="可用内存">
              {{ formatMemory(detail.availableMemoryMb) }}
            </n-descriptions-item>
          </n-descriptions>
        </n-spin>
      </n-drawer-content>
    </n-drawer>
  </div>
</template>

<style scoped lang="scss">
// 卡片网格可能超出视口，允许整页滚动
.nodes-page {
  overflow: auto;

  > * {
    flex-shrink: 0;
  }
}

.node-card {
  cursor: pointer;
  transition: border-color 0.2s;

  &:hover {
    border-color: var(--border-strong);
  }
}

.node-meta {
  color: var(--text-secondary);
  font-size: 13px;
}
</style>
