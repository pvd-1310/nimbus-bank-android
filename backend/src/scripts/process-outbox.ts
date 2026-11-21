import { runtime } from "../infrastructure/runtime.js";
import { drainOutbox } from "../modules/orders/fulfillment.service.js";
const r = runtime();
try {
  console.log(await drainOutbox(r.bank.store));
} finally {
  await r.db.terminate();
}
