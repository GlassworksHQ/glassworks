// /api/report: a device report from Kiln as a GitHub issue, for people without a GitHub account (src/report/server.ts).
// GET: a time token for the form just opened, or 503 when this deployment has no credentials. POST: the report.
import { handleGet, handlePost, RateLimit } from '../../../report/server';

export const runtime = 'nodejs';
export const dynamic = 'force-dynamic';

/** Per instance: see server.ts. */
const limit = new RateLimit();
const env = () => ({
  REPORT_APP_ID: process.env.REPORT_APP_ID,
  REPORT_APP_PRIVATE_KEY: process.env.REPORT_APP_PRIVATE_KEY,
  REPORT_APP_INSTALLATION_ID: process.env.REPORT_APP_INSTALLATION_ID,
  REPORT_GITHUB_TOKEN: process.env.REPORT_GITHUB_TOKEN,
});
const deps = () => ({ env: env(), fetch: (...a: Parameters<typeof fetch>) => fetch(...a), now: Date.now, limit });

export function GET(): Response {
  return handleGet(deps());
}

export function POST(req: Request): Promise<Response> {
  return handlePost(req, deps());
}
