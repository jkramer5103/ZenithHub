// Runs after `vite build`. Tunes the fallback page every route is served from, for slow, high-latency links:
// - the stylesheet no longer blocks the first paint, so app.html's skeleton shows while it downloads
//   (zenithBoot waits for it before revealing the app);
// - the error page and the current route's scripts are preloaded with the rest, instead of each being fetched only
//   once the scripts before it have run.
import fs from 'node:fs';

const page = 'build/index.html';
const manifest = JSON.parse(fs.readFileSync('.svelte-kit/output/client/.vite/manifest.json', 'utf8'));
const generated = fs.readFileSync('.svelte-kit/generated/build/client-optimized/app.js', 'utf8');
let html = fs.readFileSync(page, 'utf8');

const node = (n) => `.svelte-kit/generated/build/client-optimized/nodes/${n}.js`;
const startup = new Set(html.match(/\/_app\/immutable\/[^"]+/g));

// files a node needs, minus those the page loads anyway
function files(key, out = new Set()) {
	const chunk = manifest[key];
	const file = `/${chunk.file}`;
	if (out.has(file) || startup.has(file)) return out;
	out.add(file);
	for (const css of chunk.css ?? []) if (!startup.has(`/${css}`)) out.add(`/${css}`);
	for (const dep of chunk.imports ?? []) files(dep, out);
	return out;
}

const link = (file) =>
	file.endsWith('.css') ? `<link href="${file}" rel="preload" as="style">` : `<link href="${file}" rel="modulepreload">`;

// the root error page loads with the layout on every page
const always = [...files(node(1))];
for (const file of always) startup.add(file);

const dictionary = generated.match(/export const dictionary = (\{[\s\S]*?\});/);
if (!dictionary) throw new Error('No route dictionary in the generated app');
const routes = Object.entries(JSON.parse(dictionary[1])).map(([route, nodes]) => {
	const pattern = route
		.replace(/[.*+?^${}()|\\]/g, '\\$&')
		.replace(/\[\\\.\\\.\\\.[^/]+?\]/g, '.*')
		.replace(/\[[^/]+?\]/g, '[^/]+');
	const needed = new Set();
	for (const n of nodes.flat(Infinity)) if (typeof n === 'number') files(node(Math.abs(n)), needed);
	return [`^${pattern === '/' ? '/' : `${pattern}/?`}$`, [...needed]];
});

const preload = `<script>
			// this route's scripts (scripts/finish-build.js)
			(() => {
				for (const [pattern, files] of ${JSON.stringify(routes)}) {
					if (!new RegExp(pattern).test(location.pathname)) continue;
					for (const file of files) {
						const link = document.createElement('link');
						link.href = file;
						if (file.endsWith('.css')) {
							link.rel = 'preload';
							link.as = 'style';
						} else link.rel = 'modulepreload';
						document.head.append(link);
					}
					break;
				}
			})();
		</script>`;

const stylesheets = /<link href="([^"]+)" rel="stylesheet">/g;
if (!stylesheets.test(html)) throw new Error('No stylesheet links in the built page');
html = html.replace(
	stylesheets,
	(_, href) => `<link href="${href}" rel="preload" as="style">\n\t\t<link href="${href}" rel="stylesheet" media="print">`
);
html = html.replace(
	/(<link href="[^"]+" rel="modulepreload">\s*)(?![\s\S]*rel="modulepreload")/,
	`$1${always.map(link).join('\n\t\t')}\n\t\t${preload}\n\t\t`
);
fs.writeFileSync(page, html);
console.log(`finish-build: preloads for ${routes.length} routes, ${always.length} files always`);
