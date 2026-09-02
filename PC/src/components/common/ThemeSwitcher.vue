<script setup lang="ts">
import { ref } from 'vue'
import { NButton, NSelect, useMessage } from 'naive-ui'
import { useUiStore } from '@/stores/ui'
import type { ColorTheme } from '@/stores/ui'

const ui = useUiStore()
const message = useMessage()

const themes = [
  { value: '', label: '默认' },
  { value: 'morandi-green', label: '莫兰迪绿' },
  { value: 'warm-beige', label: '暖阳米' },
  { value: 'starry-purple', label: '星空暗紫' },
  { value: 'mint-fresh', label: '薄荷清新' },
  { value: 'sakura-pink', label: '昔涟' },
  { value: 'sky-blue', label: '天空蓝' },
] as const

const fileInput = ref<HTMLInputElement | null>(null)
const processing = ref(false)

const MIN_WIDTH = 1920
const MIN_HEIGHT = 1080
/** base64 后约 3.3MB，保证 localStorage（约 5MB）装得下 */
const MAX_STORE_BYTES = 2.5 * 1024 * 1024
/** 尺寸达标但仍超限时逐级降质重编码 */
const ENCODE_QUALITIES = [0.85, 0.72, 0.6, 0.45, 0.3]

function onSelect(value: string) {
  ui.setColorTheme(value as ColorTheme)
}

function openFilePicker() {
  fileInput.value?.click()
}

async function onFileChange(event: Event) {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  input.value = ''
  if (!file) return

  try {
    const blob = await processImage(file)
    applyCustomBackground(blob)
  } catch (err) {
    message.error(err instanceof Error ? err.message : '应用失败')
  }
}

function loadImage(file: File): Promise<HTMLImageElement> {
  return new Promise((resolve, reject) => {
    const url = URL.createObjectURL(file)
    const img = new Image()
    img.onload = () => {
      URL.revokeObjectURL(url)
      resolve(img)
    }
    img.onerror = () => {
      URL.revokeObjectURL(url)
      reject(new Error('无法读取图片文件'))
    }
    img.src = url
  })
}

function canvasToBlob(canvas: HTMLCanvasElement, quality: number): Promise<Blob> {
  return new Promise((resolve, reject) => {
    canvas.toBlob(
      (b) => (b ? resolve(b) : reject(new Error('图片处理失败'))),
      'image/jpeg',
      quality,
    )
  })
}

async function processImage(file: File): Promise<Blob> {
  const img = await loadImage(file)

  if (img.naturalWidth < MIN_WIDTH || img.naturalHeight < MIN_HEIGHT) {
    throw new Error(`图片分辨率需 ≥ ${MIN_WIDTH}×${MIN_HEIGHT}`)
  }

  const canvas = document.createElement('canvas')
  canvas.width = MIN_WIDTH
  canvas.height = MIN_HEIGHT
  const ctx = canvas.getContext('2d')
  if (!ctx) throw new Error('浏览器不支持 Canvas')

  const scale = Math.max(MIN_WIDTH / img.naturalWidth, MIN_HEIGHT / img.naturalHeight)
  const sw = MIN_WIDTH / scale
  const sh = MIN_HEIGHT / scale
  const sx = (img.naturalWidth - sw) / 2
  const sy = (img.naturalHeight - sh) / 2
  ctx.drawImage(img, sx, sy, sw, sh, 0, 0, MIN_WIDTH, MIN_HEIGHT)

  for (const q of ENCODE_QUALITIES) {
    const blob = await canvasToBlob(canvas, q)
    if (blob.size <= MAX_STORE_BYTES) return blob
  }
  throw new Error('图片过大，压缩后仍超 2.5MB，请换一张')
}

function applyCustomBackground(blob: Blob): void {
  processing.value = true
  const reader = new FileReader()
  reader.onload = () => {
    ui.setCustomBackgroundUrl(String(reader.result))
    message.success('自定义背景已应用（仅本机保存）')
    processing.value = false
  }
  reader.onerror = () => {
    message.error('图片读取失败')
    processing.value = false
  }
  try {
    reader.readAsDataURL(blob)
  } catch (err) {
    message.error(err instanceof Error ? err.message : '应用失败')
    processing.value = false
  }
}

function clearCustom(): void {
  ui.clearCustomBackground()
  message.success('已恢复默认首页背景')
}
</script>

<template>
  <div class="theme-switcher">
    <n-select
      :value="ui.colorTheme"
      :options="themes.map(t => ({ value: t.value, label: t.label }))"
      placeholder="选择主题"
      size="small"
      style="width: 140px"
      @update:value="onSelect"
    />
    <n-button size="small" :loading="processing" @click="openFilePicker">
      自定义背景
    </n-button>
    <n-button v-if="ui.customBackgroundUrl" size="small" quaternary @click="clearCustom">
      恢复默认
    </n-button>
    <input
      ref="fileInput"
      type="file"
      accept="image/jpeg,image/png,image/webp"
      style="display: none"
      @change="onFileChange"
    />
  </div>
</template>

<style scoped>
.theme-switcher {
  display: flex;
  align-items: center;
  gap: 8px;
}
</style>
