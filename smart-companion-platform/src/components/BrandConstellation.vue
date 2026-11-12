<template>
  <canvas ref="canvasRef" class="landing-constellation" aria-hidden="true" :data-animated="running" />
</template>

<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'
const props = defineProps<{ enabled: boolean }>()
const canvasRef = ref<HTMLCanvasElement | null>(null)
const running = ref(false)
let context: CanvasRenderingContext2D | null = null
let parent: HTMLElement | null = null
let resizeObserver: ResizeObserver | undefined
let visibilityObserver: IntersectionObserver | undefined
let themeObserver: MutationObserver | undefined
let frame = 0
let lastTime = 0
let visible = true
let width = 1
let height = 1
let color = '#2563eb'
let accent = '#087a58'
const pointer = { x: -1000, y: -1000 }
let nodes: { x: number; y: number; vx: number; vy: number; radius: number }[] = []

function draw(delta = 0) {
  if (!context) return
  const ctx = context
  ctx.clearRect(0, 0, width, height)
  for (const node of nodes) {
    node.x += node.vx * delta; node.y += node.vy * delta
    if (node.x < 0 || node.x > width) node.vx *= -1
    if (node.y < 0 || node.y > height) node.vy *= -1
    node.x = Math.max(0, Math.min(width, node.x)); node.y = Math.max(0, Math.min(height, node.y))
  }
  for (let i = 0; i < nodes.length; i++) {
    const node = nodes[i]
    for (let j = i + 1; j < nodes.length; j++) {
      const next = nodes[j]
      const distance = Math.hypot(node.x - next.x, node.y - next.y)
      if (distance > 175) continue
      ctx.globalAlpha = (1 - distance / 175) * .22
      ctx.strokeStyle = color; ctx.lineWidth = .8
      ctx.beginPath(); ctx.moveTo(node.x, node.y); ctx.lineTo(next.x, next.y); ctx.stroke()
    }
    const nearby = props.enabled && Math.hypot(node.x - pointer.x, node.y - pointer.y) < 150
    if (nearby) {
      ctx.globalAlpha = .2; ctx.strokeStyle = accent
      ctx.beginPath(); ctx.moveTo(pointer.x, pointer.y); ctx.lineTo(node.x, node.y); ctx.stroke()
    }
    ctx.globalAlpha = nearby ? .72 : .35
    ctx.fillStyle = i % 4 === 0 ? accent : color
    ctx.beginPath(); ctx.arc(node.x, node.y, nearby ? node.radius + 1 : node.radius, 0, Math.PI * 2); ctx.fill()
  }
  ctx.globalAlpha = 1
}
function tick(time: number) {
  frame = 0
  if (!running.value) return
  draw(lastTime ? Math.min((time - lastTime) / 16.67, 2) : 1)
  lastTime = time; frame = requestAnimationFrame(tick)
}
function syncAnimation() {
  running.value = Boolean(context && props.enabled && visible && !document.hidden)
  if (frame) cancelAnimationFrame(frame)
  frame = 0; lastTime = 0
  if (running.value) frame = requestAnimationFrame(tick)
  else { pointer.x = pointer.y = -1000; draw() }
}
function sizeCanvas() {
  if (!canvasRef.value || !parent || !context) return
  const rect = parent.getBoundingClientRect()
  width = Math.max(1, rect.width); height = Math.max(1, rect.height)
  const ratio = Math.min(window.devicePixelRatio || 1, 2)
  canvasRef.value.width = Math.round(width * ratio); canvasRef.value.height = Math.round(height * ratio)
  context.setTransform(ratio, 0, 0, ratio, 0, 0)
  // Keep the composition stable on resize and reproducible during visual QA.
  let seed = 91
  const random = () => { seed = (seed * 16807) % 2147483647; return (seed - 1) / 2147483646 }
  nodes = Array.from({ length: width < 640 ? 24 : 56 }, () => ({
    x: random() * width, y: random() * height,
    vx: (random() - .5) * .36, vy: (random() - .5) * .36, radius: 1.3 + random() * 1.7
  }))
  draw()
}
function updateColors() {
  if (!canvasRef.value) return
  const styles = getComputedStyle(canvasRef.value)
  color = styles.getPropertyValue('--c-primary-light').trim() || '#2563eb'
  accent = styles.getPropertyValue('--c-success').trim() || '#087a58'
  draw()
}
function onPointer(event: PointerEvent) {
  if (!props.enabled || event.pointerType === 'touch' || !parent) return
  const rect = parent.getBoundingClientRect()
  pointer.x = event.clientX - rect.left; pointer.y = event.clientY - rect.top
}
function clearPointer() { pointer.x = pointer.y = -1000 }
watch(() => props.enabled, syncAnimation)
onMounted(() => {
  parent = canvasRef.value?.parentElement || null
  try { context = canvasRef.value?.getContext('2d') || null } catch { return }
  if (!context || !parent) return
  sizeCanvas(); updateColors()
  if ('ResizeObserver' in window) { resizeObserver = new ResizeObserver(sizeCanvas); resizeObserver.observe(parent) }
  else window.addEventListener('resize', sizeCanvas)
  if ('IntersectionObserver' in window) {
    visibilityObserver = new IntersectionObserver(entries => { visible = entries[0].isIntersecting; syncAnimation() })
    visibilityObserver.observe(parent)
  }
  themeObserver = new MutationObserver(updateColors)
  themeObserver.observe(document.documentElement, { attributes: true, attributeFilter: ['data-theme'] })
  parent.addEventListener('pointermove', onPointer, { passive: true }); parent.addEventListener('pointerleave', clearPointer)
  document.addEventListener('visibilitychange', syncAnimation)
  syncAnimation()
})
onBeforeUnmount(() => {
  if (frame) cancelAnimationFrame(frame)
  running.value = false
  resizeObserver?.disconnect(); visibilityObserver?.disconnect(); themeObserver?.disconnect()
  parent?.removeEventListener('pointermove', onPointer); parent?.removeEventListener('pointerleave', clearPointer)
  document.removeEventListener('visibilitychange', syncAnimation); window.removeEventListener('resize', sizeCanvas)
})
</script>

<style scoped>
/* Ambient wash: fades toward the page edges and bottom so the constellation
   reads as background texture behind the hero copy, never as a hard panel. */
.landing-constellation {
  position: absolute; inset: 0; width: 100%; height: 100%; pointer-events: none;
  mask-image: linear-gradient(180deg, #000 0%, rgba(0, 0, 0, .82) 46%, transparent 95%), radial-gradient(ellipse 92% 84% at 50% 42%, #000 42%, transparent 100%);
  mask-composite: intersect;
  -webkit-mask-image: linear-gradient(180deg, #000 0%, rgba(0, 0, 0, .82) 46%, transparent 95%), radial-gradient(ellipse 92% 84% at 50% 42%, #000 42%, transparent 100%);
  -webkit-mask-composite: source-in;
}
</style>
