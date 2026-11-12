<script setup lang="ts">
import { computed, nextTick, ref, useId, watch } from 'vue';
import { edgeOption, moveOption, popupPlacement, type SelectOption } from './uiSelectLogic';

const props = defineProps<{ modelValue: string; options: SelectOption[]; label: string; disabled?: boolean; placeholder?: string }>();
const emit = defineEmits<{ 'update:modelValue': [value: string]; change: [value: string] }>();
const id = `ui-select-${useId()}`;
const trigger = ref<HTMLButtonElement>();
const popup = ref<HTMLElement>();
const open = ref(false);
const active = ref(-1);
const placement = ref({ left: 0, top: 0, width: 180, maxHeight: 320, flip: false });
const selected = computed(() => props.options.find(option => option.value === props.modelValue));
const popupStyle = computed(() => ({ left: `${placement.value.left}px`, top: `${placement.value.top}px`, width: `${placement.value.width}px`, maxHeight: `${placement.value.maxHeight}px`, transform: placement.value.flip ? 'translateY(-100%)' : undefined }));
function reposition() {
  if (trigger.value) placement.value = popupPlacement(trigger.value.getBoundingClientRect(), window.innerWidth, window.innerHeight);
}
function close(restoreFocus = false) {
  open.value = false;
  if (restoreFocus) trigger.value?.focus();
}
async function show(last = false) {
  if (props.disabled) return;
  const index = props.options.findIndex(option => option.value === props.modelValue && !option.disabled);
  active.value = index >= 0 ? index : edgeOption(props.options, last);
  reposition();
  open.value = true;
  await revealActive();
}
async function revealActive() {
  await nextTick();
  popup.value?.querySelector<HTMLElement>(`[data-index="${active.value}"]`)?.scrollIntoView({ block: 'nearest' });
}
function choose(index: number) {
  const option = props.options[index];
  if (!option || option.disabled) return;
  emit('update:modelValue', option.value);
  emit('change', option.value);
  close(true);
}
function keydown(event: KeyboardEvent) {
  if (props.disabled) return;
  const key = event.key;
  if (key === 'Tab') { close(); return; }
  if (key === 'Escape') { if (open.value) { event.preventDefault(); close(true); } return; }
  if (['ArrowDown', 'ArrowUp', 'Home', 'End', 'Enter', ' '].includes(key)) {
    event.preventDefault();
    if (!open.value) {
      void show(key === 'ArrowUp' || key === 'End');
      if (key === 'Home' || key === 'End') active.value = edgeOption(props.options, key === 'End');
      return;
    }
    if (key === 'Enter' || key === ' ') { choose(active.value); return; }
    active.value = key === 'Home' || key === 'End' ? edgeOption(props.options, key === 'End') : moveOption(props.options, active.value, key === 'ArrowDown' ? 1 : -1);
    void revealActive();
  } else if (key.length === 1 && !event.ctrlKey && !event.metaKey && !event.altKey) {
    // Native-select style first-letter navigation, also useful for long name lists.
    const start = open.value ? active.value : -1;
    for (let offset = 1; offset <= props.options.length; offset++) {
      const index = (start + offset) % props.options.length;
      const option = props.options[index];
      if (option && !option.disabled && option.label.toLocaleLowerCase().startsWith(key.toLocaleLowerCase())) {
        event.preventDefault(); active.value = index; reposition(); open.value = true; void revealActive(); break;
      }
    }
  }
}
watch(open, (visible, _old, cleanup) => {
  if (!visible) return;
  const outside = (event: PointerEvent) => {
    const path = event.composedPath();
    if (!path.includes(trigger.value!) && !path.includes(popup.value!)) close();
  };
  document.addEventListener('pointerdown', outside, true);
  window.addEventListener('resize', reposition);
  window.addEventListener('scroll', reposition, true);
  cleanup(() => {
    document.removeEventListener('pointerdown', outside, true);
    window.removeEventListener('resize', reposition);
    window.removeEventListener('scroll', reposition, true);
  });
});
watch(() => props.disabled, value => { if (value) close(); });
watch(() => props.options, () => {
  if (open.value) { active.value = edgeOption(props.options); void revealActive(); }
});
</script>

<template>
  <div class="ui-select">
    <span class="ui-select-label">{{ label }}</span>
    <button ref="trigger" type="button" class="ui-select-trigger" role="combobox" aria-haspopup="listbox" :aria-label="label" :aria-expanded="open" :aria-controls="id" :aria-activedescendant="open && active >= 0 ? `${id}-${active}` : undefined" :disabled="disabled" @click="open ? close() : show()" @keydown="keydown">
      <span class="ui-select-value" :title="selected?.label">{{ selected?.label || placeholder || '请选择' }}</span><span class="ui-select-chevron" aria-hidden="true">⌄</span>
    </button>
    <Teleport to="body">
      <div v-if="open" :id="id" ref="popup" class="ui-select-popup" role="listbox" :aria-label="label" :style="popupStyle" @pointerdown.prevent>
        <div v-for="(option, index) in options" :id="`${id}-${index}`" :key="option.value" class="ui-select-option" :class="{ 'is-active': index === active, 'is-selected': option.value === modelValue, 'is-disabled': option.disabled }" role="option" :aria-selected="option.value === modelValue" :aria-disabled="option.disabled || undefined" :data-index="index" :title="option.description || option.label" @pointermove="!option.disabled && (active = index)" @click="choose(index)">
          <span class="ui-select-option-copy"><span>{{ option.label }}</span><small v-if="option.description">{{ option.description }}</small></span><span v-if="option.value === modelValue" aria-hidden="true">✓</span>
        </div>
        <div v-if="!options.length" class="ui-select-empty">暂无可选项</div>
      </div>
    </Teleport>
  </div>
</template>

<style scoped>
.ui-select { display: flex; flex-direction: column; gap: 7px; min-width: 0; font: inherit; font-family: var(--font-ui, inherit); }
.ui-select-label { font-size: 12px; line-height: 1.5; color: var(--muted, #a3b3ca); }
.ui-select .ui-select-trigger { display: flex; align-items: center; justify-content: space-between; gap: 14px; width: 100%; min-width: 0; min-height: 42px; padding: 9px 12px; border: 1px solid var(--line, #2a3c53); border-radius: 8px; background: var(--control, #0b1726); color: var(--text, #eef3fc); font: inherit; font-size: 13px; text-align: left; cursor: pointer; }
.ui-select .ui-select-trigger:hover:not(:disabled), .ui-select .ui-select-trigger[aria-expanded="true"] { border-color: var(--blue, #84b9ff); background: var(--raised, #16273c); }
.ui-select-trigger:focus-visible { outline: 2px solid var(--blue, #84b9ff); outline-offset: 3px; }
.ui-select-trigger:disabled { opacity: .5; cursor: not-allowed; }
.ui-select-value { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.ui-select-chevron { flex: none; color: var(--blue, #84b9ff); }
.ui-select-popup { position: fixed; z-index: 10000; overflow-y: auto; overscroll-behavior: contain; padding: 5px; border: 1px solid var(--line, #2a3c53); border-radius: 10px; background: var(--surface, #101e30); color: var(--text, #eef3fc); box-shadow: 0 16px 38px #0007; font: inherit; font-family: var(--font-ui, inherit); font-size: 13px; line-height: 1.5; scrollbar-color: var(--line, #2a3c53) var(--surface, #101e30); }
.ui-select-option { display: flex; justify-content: space-between; gap: 12px; padding: 9px 10px; min-height: 38px; border-radius: 6px; cursor: pointer; overflow-wrap: anywhere; }
.ui-select-option.is-active { background: var(--raised, #16273c); outline: 1px solid var(--blue, #84b9ff); outline-offset: -1px; }
.ui-select-option.is-selected { color: var(--blue, #84b9ff); background: var(--blue-soft, #182f4d); }
.ui-select-option.is-disabled { opacity: .45; cursor: not-allowed; }
.ui-select-option-copy { display: flex; flex-direction: column; gap: 3px; }
.ui-select-option-copy small { color: var(--muted, #a3b3ca); font: inherit; font-size: 12px; }
.ui-select-empty { padding: 12px; color: var(--muted, #a3b3ca); }
</style>
