<template>
  <span
    class="brand-mark"
    :class="[
      `brand-mark--${variant}`,
      `brand-mark--${tone}`,
      `brand-mark--${size}`
    ]"
    :role="accessibleName ? 'img' : undefined"
    :aria-label="accessibleName || undefined"
  >
    <svg
      class="brand-mark__symbol"
      viewBox="0 0 48 48"
      fill="none"
      aria-hidden="true"
      focusable="false"
    >
      <path
        class="brand-mark__route"
        d="M8.5 35.5C13.4 35.5 15.1 31.4 19.6 26.5C23.1 22.7 26.3 24.1 29.7 20.5C33.1 16.9 33.2 11.6 39.5 9"
      />
      <path class="brand-mark__branch" d="M19.6 26.5 12.5 13" />
      <circle class="brand-mark__node brand-mark__node--start" cx="8.5" cy="35.5" r="4.25" />
      <circle class="brand-mark__node brand-mark__node--branch" cx="12.5" cy="13" r="4" />
      <circle class="brand-mark__node brand-mark__node--middle" cx="19.6" cy="26.5" r="4.5" />
      <circle class="brand-mark__target" cx="39.5" cy="9" r="5.75" />
      <circle class="brand-mark__target-core" cx="39.5" cy="9" r="2.25" />
    </svg>

    <span v-if="variant === 'horizontal'" class="brand-mark__copy">
      <span class="brand-mark__name">{{ label }}</span>
      <span v-if="tagline" class="brand-mark__tagline">{{ tagline }}</span>
    </span>
  </span>
</template>

<script setup lang="ts">
type BrandMarkVariant = 'horizontal' | 'symbol'
type BrandMarkTone = 'brand' | 'on-dark' | 'monochrome'
type BrandMarkSize = 'sm' | 'md' | 'lg'

interface BrandMarkProps {
  variant?: BrandMarkVariant
  tone?: BrandMarkTone
  size?: BrandMarkSize
  label?: string
  tagline?: string
  accessibleName?: string
}

withDefaults(defineProps<BrandMarkProps>(), {
  variant: 'horizontal',
  tone: 'brand',
  size: 'md',
  label: '智学引擎',
  tagline: '',
  accessibleName: ''
})
</script>

<style scoped>
.brand-mark {
  --brand-mark-path: var(--brand-primary, #3159c9);
  --brand-mark-accent: var(--brand-secondary, #0c8f88);
  --brand-mark-ink: var(--brand-ink, #172033);
  --brand-mark-muted: var(--brand-ink-muted, #56627a);
  --brand-mark-size: 36px;
  --brand-mark-name-size: 1.125rem;
  --brand-mark-tagline-size: 0.6875rem;

  display: inline-flex;
  align-items: center;
  gap: 0.625rem;
  color: var(--brand-mark-ink);
  font-family: var(
    --brand-font-sans,
    Inter,
    "PingFang SC",
    "Microsoft YaHei",
    system-ui,
    sans-serif
  );
  line-height: 1;
  vertical-align: middle;
}

.brand-mark__symbol {
  display: block;
  width: var(--brand-mark-size);
  height: var(--brand-mark-size);
  flex: 0 0 var(--brand-mark-size);
  overflow: visible;
}

.brand-mark__route,
.brand-mark__branch {
  stroke: var(--brand-mark-path);
  stroke-linecap: round;
  stroke-linejoin: round;
}

.brand-mark__route {
  stroke-width: 3.5;
}

.brand-mark__branch {
  stroke-width: 2.75;
  opacity: 0.82;
}

.brand-mark__node {
  fill: var(--brand-mark-path);
}

.brand-mark__node--branch {
  fill: var(--brand-mark-accent);
}

.brand-mark__target {
  fill: none;
  stroke: var(--brand-mark-accent);
  stroke-width: 3;
}

.brand-mark__target-core {
  fill: var(--brand-mark-accent);
}

.brand-mark__copy {
  display: inline-flex;
  min-width: 0;
  flex-direction: column;
  gap: 0.2em;
}

.brand-mark__name {
  color: var(--brand-mark-ink);
  font-size: var(--brand-mark-name-size);
  font-weight: 720;
  letter-spacing: 0.045em;
  line-height: 1.05;
  white-space: nowrap;
}

.brand-mark__tagline {
  color: var(--brand-mark-muted);
  font-size: var(--brand-mark-tagline-size);
  font-weight: 500;
  letter-spacing: 0.06em;
  line-height: 1.25;
  white-space: nowrap;
}

.brand-mark--symbol {
  gap: 0;
  line-height: 0;
}

.brand-mark--sm {
  --brand-mark-size: 24px;
  --brand-mark-name-size: 0.9375rem;
  --brand-mark-tagline-size: 0.625rem;

  gap: 0.45rem;
}

.brand-mark--lg {
  --brand-mark-size: 48px;
  --brand-mark-name-size: 1.45rem;
  --brand-mark-tagline-size: 0.75rem;

  gap: 0.75rem;
}

.brand-mark--on-dark {
  --brand-mark-path: var(--brand-primary-on-dark, #b9c9ff);
  --brand-mark-accent: var(--brand-secondary-on-dark, #67ddd2);
  --brand-mark-ink: var(--brand-ink-on-dark, #f7f9ff);
  --brand-mark-muted: var(--brand-ink-muted-on-dark, #bdc8db);
}

.brand-mark--monochrome {
  --brand-mark-path: currentColor;
  --brand-mark-accent: currentColor;
  --brand-mark-ink: currentColor;
  --brand-mark-muted: currentColor;
}

@media (forced-colors: active) {
  .brand-mark {
    --brand-mark-path: CanvasText;
    --brand-mark-accent: CanvasText;
    --brand-mark-ink: CanvasText;
    --brand-mark-muted: CanvasText;
  }
}
</style>
