import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

export default defineConfig({
  plugins: [vue()],
  server: {
    port: Number(process.env.VITE_DEV_PORT || 5173),
    host: '0.0.0.0',
    proxy: {
      '/api/v1': {
        target: process.env.VITE_API_PROXY_TARGET || 'http://localhost:8080',
        changeOrigin: true,
      },
    },
  },
})
