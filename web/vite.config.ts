import adapter from '@sveltejs/adapter-static';
import { sveltekit } from '@sveltejs/kit/vite';
import tailwindcss from '@tailwindcss/vite';
import { resolve } from 'node:path';
import { defineConfig } from 'vite';

// during `npm run dev`, API calls go to a manager running locally
const manager = process.env.ZENITH_MANAGER_URL ?? 'http://localhost:8080';

// Everything the root layout and error page import statically goes into one chunk: every page needs them before it
// can render, and over a slow link each extra file costs a round trip. Route code stays split per page.
let startup: Set<string> | undefined;
function startupChunk(id: string, ctx: { getModuleInfo(id: string): { importedIds: readonly string[] } | null }) {
	if (!startup) {
		startup = new Set();
		const queue = ['0', '1'].map((n) => resolve(`.svelte-kit/generated/build/client-optimized/nodes/${n}.js`));
		for (const next of queue) {
			if (startup.has(next)) continue;
			startup.add(next);
			queue.push(...(ctx.getModuleInfo(next)?.importedIds ?? []));
		}
	}
	return startup.has(id) && !/[\\/]client-optimized[\\/]nodes[\\/]/.test(id) ? 'startup' : null;
}

export default defineConfig({
	plugins: [
		tailwindcss(),
		sveltekit({
			compilerOptions: {
				// Force runes mode for the project, except for libraries. Can be removed in svelte 6.
				runes: ({ filename }) =>
					filename.split(/[/\\]/).includes('node_modules') ? undefined : true
			},
			// single page app: the manager serves index.html for every non-API path
			adapter: adapter({ fallback: 'index.html' })
		})
	],
	environments: {
		client: {
			build: {
				rolldownOptions: {
					output: {
						codeSplitting: {
							groups: [
								{ name: startupChunk },
								// small modules several pages share, so a page doesn't load a handful of tiny files
								{
									name: 'common',
									test: (id) => !startup?.has(id) && !/node_modules[\\/]@sveltejs[\\/]kit/.test(id),
									minShareCount: 2,
									maxModuleSize: 4096
								}
							]
						}
					}
				}
			}
		}
	},
	server: {
		proxy: {
			// Preserve Host so the manager can validate the browser's WebSocket Origin.
			'/api': { target: manager, ws: true, changeOrigin: false }
		}
	}
});
