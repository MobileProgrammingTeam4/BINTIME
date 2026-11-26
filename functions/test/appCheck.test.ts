import { EventEmitter } from "node:events";
import { readFileSync, readdirSync } from "node:fs";
import { join } from "node:path";
import { afterEach, describe, expect, it, vi } from "vitest";
import { setGlobalOptions } from "firebase-functions/v2";
import { onCall } from "firebase-functions/v2/https";
import { appCheckEnforced } from "../src/security/appCheck";

afterEach(() => {
  setGlobalOptions({ enforceAppCheck: false });
  vi.unstubAllEnvs();
});

describe("deployment App Check policy", () => {
  it("requires an explicit true after client rollout", () => {
    expect(appCheckEnforced({})).toBe(false);
    expect(appCheckEnforced({ APP_CHECK_ENFORCED: "false" })).toBe(false);
    expect(appCheckEnforced({ APP_CHECK_ENFORCED: "true" })).toBe(true);
  });
  it("rejects typos instead of silently disabling protection", () => {
    for (const value of ["", "TRUE", "1", " true", "treu"]) {
      expect(() => appCheckEnforced({ APP_CHECK_ENFORCED: value })).toThrow();
    }
  });
  it("only bypasses attestation in the local Functions emulator", () => {
    expect(appCheckEnforced({ FUNCTIONS_EMULATOR: "true", APP_CHECK_ENFORCED: "true" })).toBe(false);
    expect(appCheckEnforced({ FUNCTIONS_EMULATOR: "false", APP_CHECK_ENFORCED: "true" })).toBe(true);
  });
  it("loads the shared policy first and forbids per-callable overrides", () => {
    const root = join(import.meta.dirname, "../src");
    expect(readFileSync(join(root, "index.ts"), "utf8").match(/^import[^;]+;/m)?.[0])
      .toBe('import "./security/appCheck";');
    function inspect(path: string): void {
      for (const entry of readdirSync(path, { withFileTypes: true })) {
        const child = join(path, entry.name);
        if (entry.isDirectory()) inspect(child);
        else if (entry.name.endsWith(".ts") && entry.name !== "appCheck.ts") {
          expect(readFileSync(child, "utf8"), child).not.toMatch(/enforceAppCheck\s*:/);
          expect(readFileSync(child, "utf8"), child).not.toMatch(/setGlobalOptions\s*\(/);
        }
      }
    }
    inspect(root);
  });
});

// Exercise the SDK's HTTP middleware, not callable.run() (which skips App Check).
// No token verifier, network, Auth user or production Firebase project is used.
async function invoke(enforced: boolean, invalidToken = false) {
  setGlobalOptions({ enforceAppCheck: enforced });
  let called = false;
  const callable = onCall(() => { called = true; return { ok: true }; });
  const headers: Record<string, string> = { "content-type": "application/json" };
  if (invalidToken) headers["x-firebase-appcheck"] = "malformed-local-test-token";
  const responseHeaders: Record<string, string> = {};
  const response = Object.assign(new EventEmitter(), {
    statusCode: 200,
    body: {} as any,
    setHeader(name: string, value: string) { responseHeaders[name.toLowerCase()] = value; },
    getHeader(name: string) { return responseHeaders[name.toLowerCase()]; },
    status(code: number) { this.statusCode = code; return this; },
    send(body: unknown) { this.body = body; this.emit("finish"); return this; },
    end() { this.emit("finish"); },
  });
  await callable({ method: "POST", headers, body: { data: {} },
    header: (name: string) => headers[name.toLowerCase()],
  } as any, response as any);
  return { called, status: response.statusCode, body: response.body };
}

describe("SDK middleware inherits the shared switch", () => {
  it("blocks a missing token before business logic when enforcement is on", async () => {
    const result = await invoke(true);
    expect(result.called).toBe(false);
    expect(result.status).toBe(401);
    expect(result.body.error.status).toBe("UNAUTHENTICATED");
  });
  it("allows monitoring-stage requests to reach their own Auth checks", async () => {
    const result = await invoke(false);
    expect(result.called).toBe(true);
    expect(result.status).toBe(200);
  });
  it("rejects a malformed token before business logic", async () => {
    // Explicit synthetic project prevents ADC/project discovery or a metadata request.
    vi.stubEnv("FIREBASE_CONFIG", JSON.stringify({ projectId: "demo-bintime-app-check" }));
    vi.stubEnv("GCLOUD_PROJECT", "demo-bintime-app-check");
    const result = await invoke(true, true);
    expect(result.called).toBe(false);
    expect(result.status).toBe(401);
    expect(result.body.error.status).toBe("UNAUTHENTICATED");
  });
});
