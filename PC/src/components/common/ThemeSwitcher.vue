<script setup lang="ts">
import { ref } from 'vue'
import { NButton, NSelect, useMessage } from 'naive-ui'
import { useUiStore } from '@/stores/ui'
import type { ColorTheme } from '@/stores/ui'
import { requestForm } from '@/api/http'

const ui = useUiStore()
const message = useMessage()

const themes = [
  { value: '', label: '默认' },
  { value: 'morandi-green', label: '莫兰迪绿' },
  { value: 'warm-beige', label: '暖阳米' },
  { value: 'starry-purple', label: '星空暗紫' },
  { value: 'mint-fresh', label: '薄荷清新' },
  { value: 'sakura-pink', label: '昔涟' },
  { value: 'deep-ocean', label: '深海蓝' },
] as const

const fileInput = ref<HTMLInputElement | null>(null)
const uploading = ref(false)

const MIN_WIDTH = 1920
const MIN_HEIGHT = 1080
const MAX_SIZE = 5 * 1024 * 1024

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
    await uploadBlob(blob)
  } catch (err) {
    message.error(err instanceof Error ? err.message : '上传失败')
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

  const blob = await new Promise<Blob>((resolve, reject) => {
    canvas.toBlob(
      (b) => (b ? resolve(b) : reject(new Error('图片处理失败'))),
      'image/jpeg',
      0.85,
    )
  })

  if (blob.size > MAX_SIZE) {
    throw new Error('文件过大，最大 5MB')
  }
  return blob
}

async function uploadBlob(blob: Blob) {
  uploading.value = true
  try {
    const form = new FormData()
    form.append('file', blob, 'background.jpg')
    const res = await requestForm<{ url: string }>('/backgrounds/upload', form)
    ui.setCustomBackgroundUrl(res.url)
    message.success('自定义背景已应用')
  } finally {
    uploading.value = false
  }
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
    <n-button size="small" :loading="uploading" @click="openFilePicker">
      自定义背景
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
