<template>
  <div ref="container" class="af-chart" :style="{ height }" />
</template>

<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref, shallowRef, watch } from 'vue'
import * as echarts from 'echarts/core'
import { BarChart, LineChart, PieChart } from 'echarts/charts'
import {
  DataZoomComponent,
  GridComponent,
  LegendComponent,
  TitleComponent,
  TooltipComponent,
} from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'
import type { EChartsCoreOption } from 'echarts/core'

echarts.use([
  LineChart,
  BarChart,
  PieChart,
  GridComponent,
  TooltipComponent,
  LegendComponent,
  TitleComponent,
  DataZoomComponent,
  CanvasRenderer,
])

const props = withDefaults(defineProps<{ option: EChartsCoreOption; height?: string }>(), {
  height: '320px',
})

const container = ref<HTMLDivElement>()
const chart = shallowRef<ReturnType<typeof echarts.init> | null>(null)
let observer: ResizeObserver | null = null

onMounted(() => {
  if (!container.value) return
  chart.value = echarts.init(container.value)
  chart.value.setOption(props.option)
  observer = new ResizeObserver(() => chart.value?.resize())
  observer.observe(container.value)
})

watch(
  () => props.option,
  (option) => chart.value?.setOption(option, true),
  { deep: true },
)

onBeforeUnmount(() => {
  observer?.disconnect()
  chart.value?.dispose()
  chart.value = null
})
</script>

<style scoped>
.af-chart {
  width: 100%;
}
</style>
