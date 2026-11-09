#!/usr/bin/env node
/**
 * Stand-in for the Supertext AI file translation API v1, for tests, CI and
 * screenshots. Node 22+, no dependencies.
 *
 * It behaves like the live API where integrations went wrong before:
 *   - Authorization must be exactly `Supertext-Auth-Key <key>` (no prefix: 400,
 *     anything else: 401). The key `invalid` is rejected with 401.
 *   - target_lang and source_lang must be codes of spec/languages.json (the live
 *     list: `de-CH`, `es`, `zh-Hans`; sources bare): otherwise 404
 *     INVALID_LANGUAGE_PAIR, as the live API answers. Politeness on a target
 *     without it (`en-US`, `rm`): 400 FEATURE_ERROR.
 *   - GET /features lists every pair with its features, like the live API.
 *   - The file part's Content-Type must be exactly `text/html`: otherwise 415.
 *   - Documents over 1,000,000 characters: 413.
 *   - The first status poll answers `translating`, the next `done`.
 *
 * "Translations": with STAND_IN_SAMPLES=<dir>, texts listed in
 * <dir>/sample-<language>.json (keyed by the source text) come back translated.
 * Everything else comes back with `[<target_lang>] ` in front of each segment's
 * text, so untranslated text is easy to spot.
 *
 * Test hooks:
 *   - STAND_IN_RATE_LIMIT=1: the first upload gets HTTP 429 (Retry-After: 1).
 *   - A document containing `STAND-IN:error` / `STAND-IN:limit` / `STAND-IN:deleted`
 *     gets that file status (`error`, `limit_exceeded`, `deleted`).
 *   - GET /__stand-in/stats: counters and the last upload's fields.
 *   - POST /__stand-in/reset: resets counters and files.
 *
 * CLI: `node stand-in/stand-in.mjs` listens on PORT (default 8765) and prints
 * the base URL, e.g. http://127.0.0.1:8765/v1/.
 */
import http from 'node:http';
import { randomBytes } from 'node:crypto';
import { existsSync, readFileSync } from 'node:fs';
import { join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const MAX_CHARACTERS = 1_000_000;
const LANGUAGES = JSON.parse(readFileSync(fileURLToPath(new URL('../spec/languages.json', import.meta.url)), 'utf8'));
const SOURCES = new Set(LANGUAGES.sources.map((l) => l.code));
const TARGETS = new Map(LANGUAGES.targets.map((l) => [l.code, l]));
const FEATURES = {
  supported_features: LANGUAGES.sources.flatMap((s) =>
    LANGUAGES.targets
      .filter((t) => t.code.split('-')[0] !== s.code)
      .map((t) => ({ source_lang: s.code, target_lang: t.code, features: t.politeness ? ['supports_politeness', 'supports_glossary'] : ['supports_glossary'] })),
  ),
};

const norm = (s) =>
  s.replace(/&apos;|&#39;/g, "'").replace(/&nbsp;/g, ' ').replace(/<br\s*\/?>/g, '<br>').replace(/\s+/g, ' ').trim();

/**
 * @param {{ rateLimit?: boolean, samplesDir?: string, log?: (line: string) => void }} [options]
 * @returns {http.Server}
 */
export function createStandIn(options = {}) {
  const log = options.log ?? (() => {});
  const samples = new Map();
  const sampleFor = (lang) => {
    const primary = lang.toLowerCase().split(/[-_]/)[0];
    if (!samples.has(primary)) {
      const file = options.samplesDir ? join(options.samplesDir, `sample-${primary}.json`) : '';
      const entries = file && existsSync(file) ? Object.entries(JSON.parse(readFileSync(file, 'utf8'))) : [];
      samples.set(primary, new Map(entries.map(([source, translated]) => [norm(source), translated])));
    }
    return samples.get(primary);
  };

  let files = new Map();
  let stats;
  const reset = () => {
    files = new Map();
    stats = { submitted: 0, deleted: 0, rateLimited: 0, polls: 0, downloads: 0, rejected: 0, lastUpload: null };
  };
  reset();

  return http.createServer(async (req, res) => {
    const url = new URL(req.url ?? '/', 'http://stand-in');
    const path = url.pathname.replace(/^\/v1(?=\/)/, '');
    const send = (status, body, headers = {}) => {
      const json = typeof body !== 'string';
      res.writeHead(status, { 'Content-Type': json ? 'application/json' : 'text/html; charset=utf-8', ...headers });
      res.end(json ? JSON.stringify(body) : body);
    };
    const reject = (status, body) => {
      stats.rejected++;
      log(`${req.method} ${path} -> ${status} ${JSON.stringify(body)}`);
      send(status, body);
    };

    if (path === '/__stand-in/stats') return send(200, stats);
    if (path === '/__stand-in/reset' && req.method === 'POST') {
      reset();
      return send(200, {});
    }

    const auth = req.headers.authorization ?? '';
    if (!auth.startsWith('Supertext-Auth-Key ')) return reject(400, { error_code: 'INVALID_AUTHORIZATION_HEADER' });
    const key = auth.slice('Supertext-Auth-Key '.length);
    if (!/^\S+$/.test(key) || key === 'invalid' || key.startsWith('Supertext-Auth-Key')) {
      return reject(401, { error_code: 'UNAUTHORIZED' });
    }

    if (req.method === 'GET' && path === '/features') return send(200, FEATURES);

    if (req.method === 'POST' && path === '/translate/ai/file') {
      const chunks = [];
      for await (const chunk of req) chunks.push(chunk);
      if (options.rateLimit && stats.rateLimited === 0) {
        stats.rateLimited++;
        log('POST -> 429 (first upload, STAND_IN_RATE_LIMIT)');
        return send(429, { error_code: 'RATE_LIMIT_EXCEEDED' }, { 'Retry-After': '1' });
      }
      let form;
      try {
        form = await new Request('http://stand-in', { method: 'POST', headers: req.headers, body: Buffer.concat(chunks) }).formData();
      } catch (error) {
        return reject(400, { error_code: 'INVALID_MULTIPART', message: String(error.message) });
      }
      const file = form.get('file');
      if (!file || typeof file === 'string') return reject(400, { error_code: 'MISSING_FILE' });
      if (file.type !== 'text/html') return reject(415, { error_code: 'UNSUPPORTED_MEDIA_TYPE', content_type: file.type });
      const target = String(form.get('target_lang') ?? '');
      const source = form.has('source_lang') ? String(form.get('source_lang')) : null;
      const politeness = form.has('politeness') ? String(form.get('politeness')) : null;
      if (!TARGETS.has(target) || (source !== null && !SOURCES.has(source))) {
        return reject(404, { error_code: 'INVALID_LANGUAGE_PAIR', message: 'This language combination is not supported.', source_lang: source, target_lang: target, politeness });
      }
      if (politeness !== null && politeness !== 'more' && politeness !== 'less' && politeness !== 'default') {
        return reject(400, { error_code: 'INVALID_POLITENESS' });
      }
      if ((politeness === 'more' || politeness === 'less') && !TARGETS.get(target).politeness) {
        return reject(400, { error_code: 'FEATURE_ERROR', feature: 'politeness', message: "The feature 'politeness' is not supported for the requested configuration.", source_lang: source, target_lang: target });
      }
      const html = await file.text();
      if (html.length > MAX_CHARACTERS) return reject(413, { error_code: 'FILE_TOO_LARGE' });
      const id = randomBytes(8).toString('hex');
      const status = html.includes('STAND-IN:error')
        ? 'error'
        : html.includes('STAND-IN:limit')
          ? 'limit_exceeded'
          : html.includes('STAND-IN:deleted')
            ? 'deleted'
            : 'done';
      files.set(id, { html, target, status, polls: 0 });
      stats.submitted++;
      stats.lastUpload = { target_lang: target, source_lang: source, politeness, file_name: file.name, content_type: file.type };
      log(`POST target_lang=${target} source_lang=${source ?? '-'} politeness=${politeness ?? '-'} -> ${id}`);
      return send(200, { file_id: id });
    }

    const match = path.match(/^\/translate\/ai\/file\/([a-f0-9]+)(\/status|\/translation)?$/);
    if (!match || !files.has(match[1])) return reject(404, { error_code: 'NOT_FOUND' });
    const entry = files.get(match[1]);

    if (req.method === 'DELETE' && !match[2]) {
      files.delete(match[1]);
      stats.deleted++;
      return send(200, {});
    }
    if (req.method === 'GET' && match[2] === '/status') {
      stats.polls++;
      entry.polls++;
      return send(200, { status: entry.polls === 1 ? 'translating' : entry.status });
    }
    if (req.method === 'GET' && match[2] === '/translation') {
      if (entry.status !== 'done' || entry.polls < 2) return reject(404, { error_code: 'NOT_READY' });
      stats.downloads++;
      const sample = sampleFor(entry.target);
      const out = entry.html.replace(/(<div data-st-id="\d+">)([\s\S]*?)(<\/div>\n)/g, (all, open, inner, close) => {
        const translated = sample.get(norm(inner));
        if (translated !== undefined) return open + translated + close;
        if (inner.trim() === '') return all;
        return open + inner.replace(/^((?:\s*<[^>]+>)*)/, `$1[${entry.target}] `) + close;
      });
      return send(200, out);
    }
    return reject(405, { error_code: 'METHOD_NOT_ALLOWED' });
  });
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const port = Number(process.env.PORT || 8765);
  const server = createStandIn({
    rateLimit: process.env.STAND_IN_RATE_LIMIT === '1',
    samplesDir: process.env.STAND_IN_SAMPLES,
    log: (line) => console.log(line),
  });
  server.listen(port, '127.0.0.1', () => console.log(`Supertext stand-in on http://127.0.0.1:${port}/v1/`));
}
