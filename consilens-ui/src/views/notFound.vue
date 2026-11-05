<script setup>
import { useRouter } from "vue-router";
import EmptyState from "@/components/common/EmptyState.vue";

const router = useRouter();
</script>

<template>
  <div class="page-container">
    <div class="notfound-card">
      <div class="notfound-code num">404</div>
      <EmptyState
        title="页面不存在或已被移动"
        description="请检查地址是否正确，或从下方返回常用页面"
      />
      <div class="notfound-actions">
        <n-button size="small" type="primary" @click="router.push('/dashboard')">返回工作台</n-button>
        <n-button size="small" @click="router.back()">返回上一页</n-button>
      </div>
    </div>
  </div>
</template>

<style scoped lang="scss">
.notfound-card {
  margin-top: 40px;
  padding: 48px 24px;
  background: var(--card);
  border: 1px solid var(--border);
  border-radius: var(--radius-card);
  text-align: center;
}

.notfound-code {
  font-size: 44px;
  font-weight: 600;
  line-height: 1;
  color: var(--border-strong);
  margin-bottom: 8px;
}

.notfound-actions {
  display: flex;
  justify-content: center;
  gap: 8px;
}
</style>
