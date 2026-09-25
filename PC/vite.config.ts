import { fileURLToPath, URL } from 'node:url'
import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import { VitePWA } from 'vite-plugin-pwa'

export default defineConfig({
  plugins: [
    vue(),
    // EC-06 PWA 壳（generateSW 零手写 SW）：仅预缓存静态资源外壳，
    // 业务数据不离线（§9.4）；background/ 大图不进预缓存。
    VitePWA({
      registerType: 'autoUpdate',
      manifest: {
        name: 'ToneUp',
        short_name: 'ToneUp',
        // M-537：补 start_url/scope——部分浏览器（旧版 Android/Chrome、iOS Safari）
        // 缺省时不回退推断入口与作用域，会导致 PWA 安装范围异常
        start_url: '/',
        scope: '/',
        theme_color: '#2B3A67',
        background_color: '#FFFFFF',
        display: 'standalone',
        icons: [{ src: '/favicon.svg', sizes: 'any', type: 'image/svg+xml', purpose: 'any' }],
      },
      workbox: {
        globPatterns: ['**/*.{js,css,html,svg,woff2}'],
        // M-538：原 '**/background/**' 把 background/ 下各主题 UI 关键小图标（icon_*）
        // 一并扫出预缓存；按实测目录精确排除真正的背景大图/媒体，保留 icon_* 与 favicon
        globIgnores: [
          'background/*.png',
          'background/Firefly/f*.{png,gif,jpeg}',
          'background/Firefly/hh.svg',
          'background/sky/∞*.{png,svg,webp}',
          'background/xilian/*.{svg,webm}',
        ],
        navigateFallback: '/index.html',
        cleanupOutdatedCaches: true,
      },
    }),
  ],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url)),
    },
  },
  server: {
    port: 5173,
    proxy: {
      '/api': {
        target: 'http://127.0.0.1:8000',
        changeOrigin: true,
      },
    },
  },
  build: {
    // M-539：生产关闭 sourcemap——.map 会携带原始源码与内部注释，暴露实现细节
    sourcemap: false,
    chunkSizeWarningLimit: 700,
    rollupOptions: {
      output: {
        manualChunks(id: string): string | undefined {
          if (!id.includes('node_modules')) return undefined
          if (id.includes('katex') || id.includes('markdown-it') || id.includes('dompurify')) {
            return 'richtext-vendor'
          }
          if (id.includes('naive-ui') || id.includes('vueuc') || id.includes('seemly') || id.includes('vdirs') || id.includes('vooks') || id.includes('treemate') || id.includes('css-render')) {
            return 'ui-vendor'
          }
          return undefined
        },
      },
    },
  },
})