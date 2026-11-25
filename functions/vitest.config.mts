import { defineConfig } from "vitest/config";

export default defineConfig({
  test: { include: ["test/**/*.test.ts", "emulator-tests/**/*.test.ts"] },
});
