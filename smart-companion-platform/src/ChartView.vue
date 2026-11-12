<script setup lang="ts">
import { uiFontFamily } from './experienceDisplay';
import { nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import { init, use, type EChartsCoreOption, type EChartsType } from 'echarts/core';
import { BarChart, HeatmapChart, GraphChart, PieChart, LineChart } from 'echarts/charts';
import { GridComponent, TooltipComponent, LegendComponent, VisualMapComponent, AriaComponent } from 'echarts/components';
import { CanvasRenderer } from 'echarts/renderers';

use([BarChart, HeatmapChart, GraphChart, PieChart, LineChart, GridComponent, TooltipComponent, LegendComponent, VisualMapComponent, AriaComponent, CanvasRenderer]);
const props = withDefaults(defineProps<{ option: EChartsCoreOption; height?: number; label: string; motion?: boolean }>(), { motion: true });
const emit = defineEmits<{ select: [value: unknown] }>();
const element = ref<HTMLElement>();
let chart: EChartsType | undefined;
let observer: ResizeObserver | undefined;
function draw() {
  chart?.setOption({ ...props.option, textStyle:{...(props.option.textStyle as object),fontFamily:uiFontFamily}, animation: props.motion, aria: { enabled: true, label: { description: props.label } } }, true);
  chart?.resize();
}
onMounted(() => {
  chart = init(element.value!);
  chart.on('click', value => emit('select', value));
  observer = new ResizeObserver(() => chart?.resize());
  observer.observe(element.value!);
  draw();
});
watch(() => props.option, draw, { deep: true });
watch(() => props.motion, draw);
watch(() => props.height, async () => { await nextTick(); chart?.resize(); });
onBeforeUnmount(() => { observer?.disconnect(); chart?.dispose(); });
</script>

<template>
  <div ref="element" class="chart-view" role="img" :aria-label="label" :style="{ height: (height || 300) + 'px' }"></div>
</template>
