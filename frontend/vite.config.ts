import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    // dev-only convenience: lets `npm run dev` talk to a locally-running backend (Option B in
    // the README) without needing CORS at all. VITE_API_PROXY_TARGET overrides the default for
    // whichever backend port you're running against (8081/8082 in docker-compose, 8080 locally).
    proxy: {
      '/api': {
        target: process.env.VITE_API_PROXY_TARGET ?? 'http://localhost:8080',
        changeOrigin: true,
      },
    },
  },
});
