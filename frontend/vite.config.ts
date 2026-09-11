import { defineConfig, loadEnv } from 'vite';
import react from '@vitejs/plugin-react';

export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, '.', 'SQUIRE_');
  const target = env.SQUIRE_BACKEND_URL || 'http://127.0.0.1:8787';
  return {
    plugins: [react()],
    server: {
      port: 5173,
      strictPort: true,
      proxy: { '/api': target, '/v1': target },
    },
    preview: { proxy: { '/api': target, '/v1': target } },
  };
});
