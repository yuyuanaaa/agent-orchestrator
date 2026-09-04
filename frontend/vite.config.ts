import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import { fileURLToPath, URL } from 'node:url'

// https://vite.dev/config/
export default defineConfig({
  plugins: [vue()],
  resolve: {
    alias: {
      // 把 @ 映射到 src 目录，和 tsconfig.json 里的 paths 保持一致
      '@': fileURLToPath(new URL('./src', import.meta.url)),
    },
  },
  server: {
    port: 5173,
    proxy: {
      // 关键：把前端 /api 请求转发到后端，同时解决跨域 + context-path 两个问题
      // 后端 context-path 是 /api，端口 8080
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true,
        // 后端已配 context-path=/api，所以这里不要把 /api 重写掉
      },
    },
  },
  build: {
    // 拆包策略：把核心依赖从主包中分离出来，主包只保留业务代码。
    // 这样首页只下载 ~80KB 的业务代码 + 并行下载几个 vendor chunk，
    // 第三方依赖变更不再影响主包 hash（缓存更友好），TTI 也显著缩短。
    rollupOptions: {
      output: {
        manualChunks: {
          'vendor-vue': ['vue', 'vue-router', 'pinia'],
          'vendor-element-plus': ['element-plus', '@element-plus/icons-vue'],
          'vendor-http': ['axios'],
        },
      },
    },
    // 主包目标 < 500KB（vite 默认警告阈值）
    chunkSizeWarningLimit: 500,
  },
})
