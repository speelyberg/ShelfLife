// node hosting/worker.test.mjs — checks the Worker against a stand-in R2 bucket holding one 100-byte file.
import assert from 'node:assert/strict';
import worker from './worker.js';

const data = new Uint8Array(100).map((_, i) => i);
const env = { ASSETS: { async get(key, { range }) {
  if (key !== 'a.mp4') return null;
  const m = /bytes=(\d*)-(\d*)/.exec(range.get('range') ?? '');
  let r;
  if (m && m[1] === '') r = { suffix: +m[2] };
  else if (m) r = { offset: +m[1], length: m[2] === '' ? undefined : +m[2] - +m[1] + 1 };
  const start = r ? (r.offset ?? 100 - r.suffix) : 0, len = r ? (r.length ?? 100 - start) : 100;
  return { size: 100, range: r, httpEtag: '"e"', writeHttpMetadata() {}, body: new Blob([data.slice(start, start + len)]).stream() };
} } };
const get = (path, headers = {}, method = 'GET') => worker.fetch(new Request('https://speelyberg.com' + path, { method, headers }), env);

let res = await get('/shelflife/assets/a.mp4');
assert.equal(res.status, 200); assert.equal(res.headers.get('content-length'), '100'); assert.equal(res.headers.get('content-type'), 'video/mp4');
assert.equal((await res.arrayBuffer()).byteLength, 100);

res = await get('/shelflife/assets/a.mp4', { range: 'bytes=10-19' });
assert.equal(res.status, 206); assert.equal(res.headers.get('content-range'), 'bytes 10-19/100');
assert.deepEqual([...new Uint8Array(await res.arrayBuffer())], [10, 11, 12, 13, 14, 15, 16, 17, 18, 19]);

res = await get('/shelflife/assets/a.mp4', { range: 'bytes=-5' });
assert.equal(res.headers.get('content-range'), 'bytes 95-99/100');

res = await get('/shelflife/assets/a.mp4', {}, 'HEAD');
assert.equal(res.status, 200); assert.equal(res.body, null);

assert.equal((await get('/shelflife/assets/missing.mp4')).status, 404);
assert.equal((await get('/other')).status, 404);
assert.equal((await get('/shelflife/other')).status, 404);
res = await get('/shelflife');
assert.equal(res.status, 200); assert.match(res.headers.get('content-type'), /text\/html/);
const html = await res.text();
assert.match(html, /<h1>Nostalgia\. <span>Now on demand\.<\/span><\/h1>/); assert.match(html, /shot-search\.jpg\?v=\d+"/); assert.match(html, /href="https:\/\/github.com\/speelyberg\/ShelfLife"/);
res = await get('/shelflife/'); assert.equal(res.status, 302); assert.equal(res.headers.get('location'), 'https://speelyberg.com/shelflife');
res = await get('/');
assert.equal(res.status, 200); assert.match(await res.text(), /<svg[^>]+aria-label="speelyberg"/);
console.log('worker ok');
