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
        theme_color: '#2B3A67',
        background_color: '#FFFFFF',
        display: 'standalone',
        icons: [{ src: '/favicon.svg', sizes: 'any', type: 'image/svg+xml', purpose: 'any' }],
      },
      workbox: {
        globPatterns: ['**/*.{js,css,html,svg,woff2}'],
        globIgnores: ['**/background/**'],
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
    sourcemap: true,
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
