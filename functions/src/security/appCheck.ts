import { setGlobalOptions } from "firebase-functions/v2";

/** Stage client registration before enforcement. An invalid deployment value never fails open. */
export function appCheckEnforced(env: NodeJS.ProcessEnv = process.env): boolean {
  const value = env.APP_CHECK_ENFORCED;
  if (value !== undefined && value !== "true" && value !== "false") {
    throw new Error("APP_CHECK_ENFORCED must be true or false");
  }
  // Local demo tests use synthetic auth; production cannot be disabled by request data.
  if (env.FUNCTIONS_EMULATOR === "true") return false;
  return value === "true";
}

// All v2 onCall functions inherit this, including later merged notification/account APIs.
// Must execute before any Callable module is imported/exported from index.ts.
setGlobalOptions({ enforceAppCheck: appCheckEnforced() });
