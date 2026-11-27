import { defineConfig } from "vitest/config";

export default defineConfig({
  test: {
    // Ignore old review checkouts and generated files, even when stored under .gradle.
    include: ["test/**/*.test.ts", "emulator-tests/**/*.test.ts"],
  },
});
