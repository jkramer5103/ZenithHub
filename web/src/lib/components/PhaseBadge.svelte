<script lang="ts">
	import type { Runtime } from '#lib/types.js';

	let { runtime }: { runtime: Runtime } = $props();

	// what the user cares about is the account's state, not just the process
	const view = $derived.by(() => {
		const s = runtime.status;
		switch (runtime.phase) {
			case 'RUNNING':
				if (!s) return { label: 'Connecting', cls: 'bg-sky-950 text-sky-300', dot: 'bg-sky-400 animate-pulse' };
				if (s.inQueue) return { label: `Queue ${s.queuePosition}`, cls: 'bg-amber-950 text-amber-300', dot: 'bg-amber-400' };
				if (s.online) return { label: 'Online', cls: 'bg-emerald-950 text-emerald-300', dot: 'bg-emerald-400 animate-pulse' };
				if (s.connected) return { label: 'Connecting', cls: 'bg-sky-950 text-sky-300', dot: 'bg-sky-400 animate-pulse' };
				return { label: 'Offline', cls: 'bg-zinc-800/70 text-zinc-400', dot: 'bg-zinc-600' };
			case 'STARTING':
				return { label: 'Connecting', cls: 'bg-sky-950 text-sky-300', dot: 'bg-sky-400 animate-pulse' };
			case 'STOPPING':
				return { label: 'Disconnecting', cls: 'bg-zinc-800 text-zinc-300', dot: 'bg-zinc-400 animate-pulse' };
			case 'BACKOFF':
				return { label: 'Crashed', cls: 'bg-red-950 text-red-300', dot: 'bg-red-400' };
			default:
				return { label: 'Offline', cls: 'bg-zinc-800/70 text-zinc-400', dot: 'bg-zinc-600' };
		}
	});
</script>

<span class="inline-flex items-center gap-1.5 rounded-full px-2.5 py-0.5 text-xs font-medium whitespace-nowrap {view.cls}">
	<span class="size-1.5 rounded-full {view.dot}"></span>
	{view.label}
</span>
