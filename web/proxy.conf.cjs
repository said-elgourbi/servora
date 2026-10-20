// Public API namespaces; browser pages live under /work.
const target = process.env.SERVORA_API_TARGET || 'http://localhost:3000';
module.exports = Object.fromEntries(
  [
    '/auth/**',
    '/home/**',
    '/jobs/**',
    '/customers',
    '/customers/**',
    '/technicians',
    '/schedule',
  ].map((path) => [path, { target, secure: false, changeOrigin: false }]),
);
