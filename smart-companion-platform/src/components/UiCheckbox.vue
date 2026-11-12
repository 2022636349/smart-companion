<script setup lang="ts">
defineProps<{ modelValue: boolean; label: string; disabled?: boolean }>();
const emit = defineEmits<{ 'update:modelValue': [value: boolean] }>();
</script>

<template>
  <label class="ui-checkbox" :class="{ 'is-disabled': disabled }">
    <input type="checkbox" :checked="modelValue" :disabled="disabled" @change="emit('update:modelValue', ($event.target as HTMLInputElement).checked)" />
    <span>{{ label }}</span>
  </label>
</template>

<style scoped>
.ui-checkbox { display: inline-flex; flex-direction: row; align-items: center; gap: 9px; min-height: 36px; margin: 0; font: inherit; font-family: var(--font-ui, inherit); font-size: 13px; line-height: 1.5; color: var(--text, #eef3fc); cursor: pointer; }
.ui-checkbox input[type="checkbox"] { appearance: none; position: relative; display: grid; place-content: center; width: 18px; height: 18px; min-height: 18px; flex: 0 0 18px; margin: 0; padding: 0; border: 1px solid var(--line, #2a3c53); border-radius: 5px; background: var(--control, #0b1726); cursor: inherit; }
.ui-checkbox input:checked { background: var(--primary, #2865e9); border-color: var(--blue, #84b9ff); }
.ui-checkbox input:checked::after { content: ''; width: 8px; height: 4px; border: solid #fff; border-width: 0 0 2px 2px; transform: translateY(-1px) rotate(-45deg); }
.ui-checkbox:hover input:not(:disabled) { border-color: var(--blue, #84b9ff); }
.ui-checkbox input:focus-visible { outline: 2px solid var(--blue, #84b9ff); outline-offset: 3px; }
.ui-checkbox.is-disabled { opacity: .5; cursor: not-allowed; }
@media (forced-colors: active) { .ui-checkbox input:checked { background: Highlight; } .ui-checkbox input:checked::after { border-color: HighlightText; } }
</style>
