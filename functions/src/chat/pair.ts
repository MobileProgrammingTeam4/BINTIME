import { createHash } from "node:crypto";

/** BT-033/038/043: pair identity must not depend on participant order. */
export const chatPairKey = (uids: readonly string[]) => createHash("sha256").update(JSON.stringify([...uids].sort())).digest("hex");
