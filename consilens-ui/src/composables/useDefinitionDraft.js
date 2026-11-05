import { ref, watch } from "vue";

// ===== 草稿持久化：非编辑模式下自动保存/恢复，误关页面不丢填写内容 =====
const DRAFT_KEY = "consilens-wizard-draft";
const DRAFT_VERSION = 1;

export function useDefinitionDraft({
  editId,
  currentStep,
  planForm,
  compareForm,
  advForm,
  platformForm,
  runForm,
  defForm,
  resetForms,
  message,
}) {
  const draftRestored = ref(false);

  function writeDraft() {
    // draftSuspended 期间（清空草稿后的抑制窗口）不能写回：否则刚清掉的默认表单
    // 会在 400ms 后被定时器重新存下来，下次进页面又提示"已恢复草稿"。
    if (editId.value || draftSuspended) return;
    try {
      localStorage.setItem(
        DRAFT_KEY,
        JSON.stringify({
          version: DRAFT_VERSION,
          currentStep: currentStep.value,
          planForm,
          compareForm,
          advForm,
          platformForm,
          runForm,
          defForm,
        })
      );
    } catch {
      // 存储不可用（隐私模式等）时静默
    }
  }

  let draftTimer = null;
  // 清空草稿后的抑制窗口：resetForms 触发的 watch 不再把默认表单写回存储
  let draftSuspended = false;
  watch(
    [planForm, compareForm, advForm, platformForm, runForm, defForm, currentStep],
    () => {
      if (editId.value || draftSuspended) return;
      clearTimeout(draftTimer);
      draftTimer = setTimeout(writeDraft, 400);
    },
    { deep: true }
  );

  function restoreDraft() {
    if (editId.value) return false;
    try {
      const raw = localStorage.getItem(DRAFT_KEY);
      if (!raw) return false;
      const draft = JSON.parse(raw);
      if (draft.version !== DRAFT_VERSION) return false;
      Object.assign(planForm, draft.planForm || {});
      Object.assign(compareForm, draft.compareForm || {});
      Object.assign(advForm, draft.advForm || {});
      Object.assign(platformForm, draft.platformForm || {});
      Object.assign(runForm, draft.runForm || {});
      Object.assign(defForm, draft.defForm || {});
      currentStep.value = draft.currentStep || 1;
      return true;
    } catch {
      return false;
    }
  }

  // 保存成功后仅移除存储，不重置当前表单。
  // 必须同时取消已排队的写入：保存成功后表单还在，挂着的定时器会把内容再写回存储。
  function removeStoredDraft() {
    clearTimeout(draftTimer);
    draftTimer = null;
    localStorage.removeItem(DRAFT_KEY);
  }

  function clearDraft() {
    draftSuspended = true;
    clearTimeout(draftTimer);
    draftTimer = null;
    localStorage.removeItem(DRAFT_KEY);
    resetForms();
    draftRestored.value = false;
    message.success("草稿已清空");
    setTimeout(() => {
      draftSuspended = false;
    }, 600);
  }

  return { draftRestored, restoreDraft, removeStoredDraft, clearDraft };
}
