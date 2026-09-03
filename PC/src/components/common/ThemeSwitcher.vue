<script setup lang="ts">
import { ref } from 'vue'
import { NButton, NSelect, useMessage } from 'naive-ui'
import { useUiStore } from '@/stores/ui'
import type { ColorTheme } from '@/stores/ui'
import { saveBackground } from '@/utils/backgroundStore'

const ui = useUiStore()
const message = useMessage()

const themes = [
  { value: '', label: '默认' },
  { value: 'firefly', label: '流萤' },
  { value: 'warm-beige', label: '暖阳米' },
  { value: 'starry-purple', label: '星空暗紫' },
  { value: 'mint-fresh', label: '薄荷清新' },
  { value: 'sakura-pink', label: '昔涟' },
  { value: 'sky-blue', label: '天空蓝' },
] as const

const fileInput = ref<HTMLInputElement | null>(null)
const processing = ref(false)

/** 直接原样入库上限：≤10MB 不重编码，保留原始画质与 GIF 动画 */
const MAX_STORE_BYTES = 10 * 1024 * 1024
/** 超限重编码时的最长边与 JPEG 降质梯度 */
const MAX_EDGE = 2560
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
  if (!file.type.startsWith('image/')) {
    message.error('请选择图片文件')
    return
  }

  processing.value = true
  try {
    const blob = await processImage(file)
    await saveBackground(blob)
    ui.setCustomBackgroundUrl(URL.createObjectURL(blob))
    message.success('自定义背景已应用（仅本机保存）')
  } catch (err) {
    message.error(err instanceof Error ? err.message : '应用失败')
  } finally {
    processing.value = false
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

/**
 * 放宽后的处理规则：
 * - 不限分辨率与宽高比，≤10MB 的图直接原样保存（保留 GIF 动画、透明通道等）
 * - 超过 10MB 才重编码：先限制最长边 2560，仍超限则逐级降质、再逐级缩边
 */
async function processImage(file: File): Promise<Blob> {
  if (file.size <= MAX_STORE_BYTES) return file

  const img = await loadImage(file)
  const scale = Math.min(1, MAX_EDGE / Math.max(img.naturalWidth, img.naturalHeight))
  let canvas = document.createElement('canvas')
  canvas.width = Math.max(1, Math.round(img.naturalWidth * scale))
  canvas.height = Math.max(1, Math.round(img.naturalHeight * scale))
  const ctx = canvas.getContext('2d')
  if (!ctx) throw new Error('浏览器不支持 Canvas')
  ctx.drawImage(img, 0, 0, canvas.width, canvas.height)

  while (true) {
    for (const q of ENCODE_QUALITIES) {
      const blob = await canvasToBlob(canvas, q)
      if (blob.size <= MAX_STORE_BYTES) return blob
    }
    if (canvas.width <= 640) break
    const next = document.createElement('canvas')
    next.width = Math.round(canvas.width * 0.7)
    next.height = Math.round(canvas.height * 0.7)
    const nctx = next.getContext('2d')
    if (!nctx) break
    nctx.drawImage(canvas, 0, 0, next.width, next.height)
    canvas = next
  }
  throw new Error('图片过大，压缩后仍超出存储限制，请换一张')
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
      accept="image/*"
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
