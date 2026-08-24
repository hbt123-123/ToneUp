import { fileURLToPath, URL } from 'node:url'
import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

export default defineConfig({
  plugins: [vue()],
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
