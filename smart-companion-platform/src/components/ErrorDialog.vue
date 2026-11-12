<script setup lang="ts">
import { nextTick, onBeforeUnmount, ref, watch } from 'vue';
import { clearErrors, currentError, dismissError } from '../notification';
const dialog = ref<HTMLDialogElement>();
const confirm = ref<HTMLButtonElement>();
let restore: HTMLElement | null = null;
let generation = 0;
function restoreFocus() {
  const target = restore;
  restore = null;
  if (target?.isConnected && !target.matches(':disabled, [inert], [aria-disabled="true"]') && !target.closest('[inert]')) target.focus();
}
function dismiss() { if (currentError.value) dismissError(currentError.value.id); }
watch(currentError, async notice => {
  const turn = ++generation;
  dialog.value?.close();
  restoreFocus();
  if (!notice) return;
  restore = notice.opener;
  await nextTick();
  if (turn !== generation || !dialog.value?.isConnected) return;
  dialog.value.showModal();
  confirm.value?.focus();
}, { flush: 'post', immediate: true });
onBeforeUnmount(() => { ++generation; dialog.value?.close(); restoreFocus(); clearErrors(); });
</script>

<template>
  <dialog ref="dialog" class="error-dialog" data-testid="error-dialog" role="alertdialog" aria-modal="true" aria-labelledby="global-error-title" aria-describedby="global-error-message" @cancel.prevent="dismiss">
    <div class="error-panel">
      <button type="button" class="error-close" aria-label="关闭错误提示" @click="dismiss">×</button>
      <div class="error-heading">
        <span class="error-icon" aria-hidden="true"><svg viewBox="0 0 24 24" fill="none"><circle cx="12" cy="12" r="9"/><path d="M12 7v6m0 3v1"/></svg></span>
        <div><span class="error-eyebrow">操作提示</span><h2 id="global-error-title">{{ currentError?.title }}</h2></div>
      </div>
      <p id="global-error-message" class="error-message">{{ currentError?.message }}</p>
      <footer><button ref="confirm" type="button" class="error-confirm" @click="dismiss">确定</button></footer>
    </div>
  </dialog>
</template>

<style scoped>
.error-dialog{box-sizing:border-box;width:min(480px,calc(100vw - 32px));max-width:calc(100vw - 32px);max-height:calc(100dvh - 32px);padding:0;border:1px solid #355984;border-radius:20px;background:#102540;color:#eef5ff;box-shadow:0 24px 80px #020c20a6;font-family:inherit;overflow:auto}
.error-dialog::backdrop{background:#030e22ad;backdrop-filter:blur(4px)}
.error-panel{position:relative;padding:30px 26px 24px;min-width:0}
.error-heading{display:flex;align-items:center;gap:14px;padding-right:24px}
.error-icon{display:grid;place-items:center;width:46px;height:46px;flex-shrink:0;background:#263d58;border:1px solid #647488;border-radius:14px;color:#ffd3a1}
.error-icon svg{width:27px;height:27px;stroke:currentColor;stroke-width:1.8;stroke-linecap:round}
.error-eyebrow{font-size:11px;letter-spacing:.12em;color:#9fb8da}
.error-dialog h2{margin:5px 0 0;font-size:20px;line-height:1.4;color:#f2f7ff;overflow-wrap:anywhere}
.error-message{margin:24px 0;max-height:45dvh;overflow:auto;white-space:pre-wrap;overflow-wrap:anywhere;font-size:14px;line-height:1.85;color:#dce7f7}
.error-close{position:absolute;right:12px;top:10px;width:36px;height:36px;padding:0;border:0;border-radius:10px;background:transparent;color:#b9cae1;font-size:26px;cursor:pointer}
footer{display:flex;justify-content:flex-end}
.error-confirm{min-width:100px;min-height:42px;padding:10px 24px;border:1px solid #678ed0;border-radius:11px;background:#355f9f;color:white;font:inherit;font-weight:600;cursor:pointer}
.error-close:hover,.error-confirm:hover{background:#426fae;color:white}
button:focus-visible{outline:3px solid #a9cbff;outline-offset:3px}
@media(max-width:430px){.error-panel{padding:26px 20px 20px}.error-dialog h2{font-size:18px}.error-heading{gap:12px}}
</style>
