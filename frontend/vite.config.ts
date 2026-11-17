import tailwindcss from '@tailwindcss/vite'
import react from '@vitejs/plugin-react'
import { defineConfig } from 'vitest/config'

// The browser stays on http://localhost:5173. changeOrigin stays off so
// Spring sees that host and builds the OAuth redirect URI for it.
const api = {
  target: 'http://127.0.0.1:8080',
  changeOrigin: false,
}

// https://vite.dev/config/
export default defineConfig({
  plugins: [react(), tailwindcss()],
  server: {
    host: 'localhost',
    port: 5173,
    proxy: {
      '/api': api,
      '/oauth2': api,
      '/login/oauth2': api,
    },
  },
  test: {
    environment: 'node',
    include: ['src/**/*.test.ts', 'src/**/*.test.tsx'],
    setupFiles: ['src/test/setup.ts'],
  },
})
