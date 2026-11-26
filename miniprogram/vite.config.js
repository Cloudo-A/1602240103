import { defineConfig } from 'vite'
import { fileURLToPath, URL } from 'node:url'
import uni from '@dcloudio/vite-plugin-uni'

// @shared：与网页共用的状态名称、格式化、请求标识（E:\adc-system\shared）
// H5 只用于本机调试：/api 转发到本机后端（localhost:8080），需要看线上数据时设环境变量 MP_API=https://zengziyu.cn
// 微信小程序正式包直接请求 https://zengziyu.cn/api（见 src/config.js）
export default defineConfig({
  plugins: [uni()],
  resolve: {
    alias: { '@shared': fileURLToPath(new URL('../shared', import.meta.url)) }
  },
  server: {
    port: 5176,
    proxy: {
      '/api': { target: process.env.MP_API || 'http://localhost:8080', changeOrigin: true, secure: true }
    }
  }
})
