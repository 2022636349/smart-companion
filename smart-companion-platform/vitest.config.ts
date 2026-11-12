import { defineConfig } from 'vitest/config';
export default defineConfig({ cacheDir: 'D:/PackageCache/vitest/smart-companion-platform', test: { environment: 'node', include: ['src/**/*.spec.ts'] } });
