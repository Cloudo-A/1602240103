import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import { fileURLToPath, URL } from 'node:url'

export default defineConfig({
  plugins: [vue()],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url)),
      // 网页和小程序共用的状态文字、格式化等代码
      '@shared': fileURLToPath(new URL('../shared', import.meta.url))
    }
  },
  server: {
    port: 5173,
    strictPort: true,
    // shared 目录在工程外，需要允许开发服务器读取
    fs: { allow: ['..'] },
    // 关闭模拟数据时，/api 请求转发给本机后端（协作约定第 2 节）
    proxy: {
      '/api': { target: 'http://localhost:8080', changeOrigin: true }
    }
  },
  build: {
    chunkSizeWarningLimit: 1500
  }
})
