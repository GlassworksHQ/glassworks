// The S1's release route before #28, kept for manifests staged before it: the same request on
// /api/release/aqara-s1-plus, which serves and refuses the same requests with the same status codes.
export function GET(req: Request): Response {
  const u = new URL(req.url);
  return Response.redirect(new URL(`/api/release/aqara-s1-plus${u.search}`, u), 308);
}
