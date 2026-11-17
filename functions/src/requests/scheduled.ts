import { getApps, initializeApp } from "firebase-admin/app";
import { getFirestore } from "firebase-admin/firestore";
import { onSchedule } from "firebase-functions/v2/scheduler";
import { logger } from "firebase-functions";
import { RequestMaintenance } from "./maintenance";

export function createRequestMaintenanceSchedule() {
  return onSchedule({ schedule: "every 5 minutes", timeZone: "Asia/Seoul", region: "asia-northeast3",
    maxInstances: 1, minInstances: 0, retryCount: 0, timeoutSeconds: 120 }, async () => {
    const app = getApps().find(a => a.name === "[DEFAULT]") ?? initializeApp();
    const result = await new RequestMaintenance(getFirestore(app)).runBatch();
    if (result.failed) logger.error("request_maintenance_partial_failure", result);
    else logger.info("request_maintenance_completed", result);
  });
}
