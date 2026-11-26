#!/usr/bin/env node
/**
 * Compares Supertext's live language list (GET features, cost-free) with spec/languages.json:
 * sources, targets and which targets support politeness. Prints the differences as Markdown
 * (and to $GITHUB_STEP_SUMMARY) and exits 1 when there are any, 2 when the API can't be asked.
 * Never prints the key. See docs/DEVELOPER.md → Language list check.
 *
 *   SUPERTEXT_API_KEY=… node scripts/check-languages.mjs [--out report.md]
 */
import { readFileSync, writeFileSync, appendFileSync } from 'node:fs';

const key = (process.env.SUPERTEXT_API_KEY ?? '').replace(/^\s*Supertext-Auth-Key\s*/i, '').trim();
const base = (process.env.SUPERTEXT_API_URL || 'https://api.supertext.com/v1/').replace(/\/?$/, '/');
const outIndex = process.argv.indexOf('--out');
const out = outIndex > 0 ? process.argv[outIndex + 1] : null;
if (!key) {
	console.log('::error::SUPERTEXT_API_KEY is not set');
	process.exit(2);
}

const spec = JSON.parse(readFileSync(new URL('../spec/languages.json', import.meta.url), 'utf8'));
const res = await fetch(`${base}features`, { headers: { Authorization: `Supertext-Auth-Key ${key}`, 'User-Agent': 'Supertext-SDK-Language-Check' } });
if (!res.ok) {
	console.log(`::error::GET features answered HTTP ${res.status}`);
	process.exit(2);
}
const pairs = (await res.json()).supported_features ?? [];

const liveSources = new Set();
const liveTargets = new Map(); // code → supports politeness (with any source)
for (const p of pairs) {
	if (p.source_lang) liveSources.add(p.source_lang);
	if (!p.target_lang) continue;
	const polite = (p.features ?? []).includes('supports_politeness');
	liveTargets.set(p.target_lang, (liveTargets.get(p.target_lang) ?? false) || polite);
}
const specSources = new Set(spec.sources.map((s) => s.code));
const specTargets = new Map(spec.targets.map((t) => [t.code, t.politeness === true]));
const sorted = (xs) => [...xs].sort();

const lines = [];
const list = (title, codes) => { if (codes.length) lines.push(`**${title}:** ${codes.map((c) => `\`${c}\``).join(', ')}`, ''); };
list('New sources (live, not in spec/languages.json)', sorted([...liveSources].filter((c) => !specSources.has(c))));
list('Sources gone (in spec/languages.json, not live)', sorted([...specSources].filter((c) => !liveSources.has(c))));
list('New targets', sorted([...liveTargets.keys()].filter((c) => !specTargets.has(c))).map((c) => `${c}${liveTargets.get(c) ? ' (formal/informal)' : ''}`));
list('Targets gone', sorted([...specTargets.keys()].filter((c) => !liveTargets.has(c))));
list('Politeness changed', sorted([...liveTargets.keys()].filter((c) => specTargets.has(c) && specTargets.get(c) !== liveTargets.get(c))).map((c) => `${c} → ${liveTargets.get(c) ? 'supports' : 'no'} formal/informal`));

const report = lines.length
	? ['Supertext\'s live language list (`GET features`) differs from `spec/languages.json`:', '', ...lines,
		'Update `spec/languages.json`, all six SDKs, their tests, the guide and the changelog, release the SDK and copy it into the plugins (docs/DEVELOPER.md → *Language list check*).', ''].join('\n')
	: `The live language list matches \`spec/languages.json\` (${liveSources.size} sources, ${liveTargets.size} targets).\n`;
console.log(report);
if (out) writeFileSync(out, report);
if (process.env.GITHUB_STEP_SUMMARY) appendFileSync(process.env.GITHUB_STEP_SUMMARY, report);
process.exit(lines.length ? 1 : 0);
