import { fileURLToPath, URL } from 'node:url'
import { defineConfig, loadEnv } from 'vite'
import vue from '@vitejs/plugin-vue'

// Vite 配置：/api 代理到后端（默认 http://127.0.0.1:8080），可用 VITE_PROXY_TARGET 覆盖
// 例：VITE_PROXY_TARGET=http://127.0.0.1:8081 pnpm dev
export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), '')
  const proxyTarget =
    process.env.VITE_PROXY_TARGET || env.VITE_PROXY_TARGET || 'http://127.0.0.1:8080'

  return {
    plugins: [vue()],
    resolve: {
      alias: {
        '@': fileURLToPath(new URL('./src', import.meta.url)),
      },
    },
    server: {
      host: '127.0.0.1',
      port: 5173,
      strictPort: false,
      proxy: {
        '/api': {
          target: proxyTarget,
          changeOrigin: true,
        },
      },
    },
    build: {
      outDir: 'dist',
      sourcemap: false,
      chunkSizeWarningLimit: 1600,
      rollupOptions: {
        output: {
          manualChunks: {
            vue: ['vue', 'vue-router', 'pinia'],
            element: ['element-plus', '@element-plus/icons-vue'],
            charts: ['echarts'],
            markdown: ['markdown-it', 'highlight.js'],
          },
        },
      },
    },
  }
})
