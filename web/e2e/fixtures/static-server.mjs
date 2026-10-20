// Test-only production SPA server: fixture responses, or an opt-in connected API proxy.
import { createServer, request } from 'node:http';
import { readFile } from 'node:fs/promises';
import { extname, resolve, sep } from 'node:path';
const root = resolve('dist/servora-web/browser');
const mime = {
  '.html': 'text/html',
  '.js': 'text/javascript',
  '.css': 'text/css',
  '.json': 'application/json',
  '.webmanifest': 'application/manifest+json',
  '.png': 'image/png',
  '.svg': 'image/svg+xml',
};
createServer(async (req, res) => {
  const path = new URL(req.url, 'http://127.0.0.1').pathname;
  res.setHeader('Cache-Control', 'no-store');
  if (
    process.env.SERVORA_CONNECTED_API &&
    /^\/(auth|home|jobs|customers|technicians|schedule)(\/|$)/.test(path)
  ) {
    const upstream = request(
      new URL(req.url, process.env.SERVORA_CONNECTED_API),
      {
        method: req.method,
        headers: req.headers,
      },
      (response) => {
        res.writeHead(response.statusCode, response.headers);
        response.pipe(res);
      },
    );
    upstream.on('error', () => {
      res.writeHead(502);
      res.end();
    });
    req.pipe(upstream);
    return;
  }
  if (path.startsWith('/auth/')) {
    res.writeHead(401, { 'Content-Type': 'application/json' });
    res.end('{"code":"UNAUTHENTICATED"}');
    return;
  }
  let file = resolve(root, `.${path}`);
  if (!file.startsWith(root + sep) && file !== root) {
    res.writeHead(403);
    res.end();
    return;
  }
  if (!extname(path)) file = resolve(root, 'index.html');
  try {
    const content = await readFile(file);
    res.setHeader('Content-Type', mime[extname(file)] || 'application/octet-stream');
    res.end(content);
  } catch {
    res.writeHead(404);
    res.end();
  }
}).listen(4300, '127.0.0.1');
