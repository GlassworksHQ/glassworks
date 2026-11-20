// The first installer page's release route before #28, kept for manifests staged before it: the same request on
// /api/release/<its line> (installer.json `legacy.releaseAsset`), which serves and refuses the same requests with the
// same status codes.
import { LEGACY_LINE } from '../release/lines';

export function GET(req: Request): Response {
  const u = new URL(req.url);
  return Response.redirect(new URL(`/api/release/${LEGACY_LINE}${u.search}`, u), 308);
}
