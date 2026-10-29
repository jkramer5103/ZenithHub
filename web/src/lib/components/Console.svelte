<script lang="ts">
	import { ansiToHtml } from '#lib/ansi.js';
	import { api } from '#lib/api.js';
	import { app } from '#lib/app.svelte.js';
	import { clock } from '#lib/format.js';
	import { toasts } from '#lib/toast.svelte.js';
	import type { CommandResult, LogLine } from '#lib/types.js';
	import { ArrowDown, ChevronRight, MessageSquare, SendHorizontal, Terminal } from '@lucide/svelte';
	import { onDestroy, tick } from 'svelte';
	import EmbedView from './EmbedView.svelte';

	type Mode = 'console' | 'chat';
	let {
		instanceId,
		mode,
		canCommand,
		canChat,
		running
	}: { instanceId: string; mode: Mode; canCommand: boolean; canChat: boolean; running: boolean } = $props();

	const MAX_ENTRIES = 2000;

	type Entry =
		| { kind: 'log'; key: string; line: LogLine }
		| { kind: 'command'; key: string; input: string; ts: number; result: CommandResult | null; error: string | null };

	let entries = $state<Entry[]>([]);
	let lastSeq = -1;
	let commandCounter = 0;

	let scroller: HTMLDivElement | undefined = $state();
	let atBottom = $state(true);
	let input = $state('');
	let sending = $state(false);
	let history: string[] = [];
	let historyIndex = -1;

	const visible = $derived(
		mode === 'chat'
			? entries.filter((e) => e.kind === 'log' && e.line.logger === 'Chat')
			: entries
	);

	function push(newEntries: Entry[]) {
		const merged = entries.concat(newEntries);
		entries = merged.length > MAX_ENTRIES ? merged.slice(merged.length - MAX_ENTRIES) : merged;
		if (atBottom) scrollToBottom();
	}

	function onLine(line: LogLine) {
		// the manager restarted: sequence numbers start over
		if (line.seq <= lastSeq && line.seq < 5) lastSeq = -1;
		if (line.seq <= lastSeq) return;
		lastSeq = line.seq;
		push([{ kind: 'log', key: `l${line.seq}`, line }]);
	}

	async function loadHistory() {
		try {
			const lines = await api.get<LogLine[]>(`/instances/${instanceId}/logs?limit=1000`);
			const commands = entries.filter((e) => e.kind === 'command');
			entries = [];
			lastSeq = -1;
			// keep our own command results; they aren't part of the server-side log
			push([...lines.map((line) => ({ kind: 'log' as const, key: `l${line.seq}`, line })), ...commands]);
			lastSeq = lines.at(-1)?.seq ?? -1;
			await scrollToBottom();
		} catch (e) {
			toasts.error(e);
		}
	}

	// subscribe first so nothing falls between the history fetch and the live stream
	const unsubscribe = app.onLog(instanceId, onLine);
	const unReconnect = app.onReconnect(loadHistory);
	loadHistory();
	onDestroy(() => {
		unsubscribe();
		unReconnect();
	});

	async function scrollToBottom() {
		await tick();
		if (scroller) scroller.scrollTop = scroller.scrollHeight;
	}

	function onScroll() {
		if (!scroller) return;
		atBottom = scroller.scrollHeight - scroller.scrollTop - scroller.clientHeight < 40;
	}

	async function submit(e: SubmitEvent) {
		e.preventDefault();
		const text = input.trim();
		if (!text || sending) return;
		sending = true;
		try {
			if (mode === 'chat') {
				await api.post(`/instances/${instanceId}/chat`, { message: text });
				input = '';
			} else {
				history = [text, ...history.filter((h) => h !== text)].slice(0, 50);
				historyIndex = -1;
				input = '';
				const key = `c${commandCounter++}`;
				push([{ kind: 'command', key, input: text, ts: Date.now(), result: null, error: null }]);
				const entry = entries.find((x) => x.key === key) as Extract<Entry, { kind: 'command' }>;
				try {
					entry.result = await api.post<CommandResult>(`/instances/${instanceId}/command`, { command: text });
				} catch (err) {
					entry.error = err instanceof Error ? err.message : String(err);
				}
				if (atBottom) scrollToBottom();
			}
		} catch (err) {
			toasts.error(err);
		} finally {
			sending = false;
		}
	}

	function onKey(e: KeyboardEvent) {
		if (mode !== 'console' || history.length === 0) return;
		if (e.key === 'ArrowUp') {
			e.preventDefault();
			historyIndex = Math.min(historyIndex + 1, history.length - 1);
			input = history[historyIndex];
		} else if (e.key === 'ArrowDown') {
			e.preventDefault();
			historyIndex = Math.max(historyIndex - 1, -1);
			input = historyIndex === -1 ? '' : history[historyIndex];
		}
	}

	function levelClass(level: string) {
		if (level === 'ERROR') return 'text-red-400';
		if (level === 'WARN') return 'text-amber-300';
		return '';
	}

	const canSend = $derived(running && (mode === 'chat' ? canChat : canCommand));
</script>

<div class="card flex min-h-0 flex-1 flex-col overflow-hidden">
	<div class="flex items-center gap-2 border-b border-zinc-800 px-3 py-2 text-sm font-medium text-zinc-300">
		{#if mode === 'chat'}<MessageSquare size={15} /> Chat{:else}<Terminal size={15} /> Console{/if}
	</div>

	<div class="relative min-h-0 flex-1">
		<div
			bind:this={scroller}
			onscroll={onScroll}
			class="absolute inset-0 overflow-y-auto bg-zinc-950/70 px-3 py-2 font-mono text-[12.5px] leading-relaxed"
		>
			{#if visible.length === 0}
				<div class="py-10 text-center font-sans text-sm text-zinc-500">
					{mode === 'chat' ? (running ? 'No chat messages yet.' : 'Connect to see chat.') : running ? 'Waiting for output…' : 'Not running.'}
				</div>
			{/if}
			{#each visible as entry (entry.key)}
				{#if entry.kind === 'log'}
					<div class="flex gap-2 break-words whitespace-pre-wrap">
						<span class="shrink-0 text-zinc-600 select-none">{clock(entry.line.ts)}</span>
						<span class="min-w-0 flex-1 {levelClass(entry.line.level)}">
							{#if mode === 'console' && entry.line.logger !== 'Chat'}<span class="hidden text-zinc-500 sm:inline">[{entry.line.logger}] </span>{/if}{@html ansiToHtml(entry.line.ansi || entry.line.text)}
						</span>
					</div>
				{:else}
					<div class="my-1.5">
						<div class="flex gap-2 text-emerald-400">
							<span class="shrink-0 text-zinc-600 select-none">{clock(entry.ts)}</span>
							<span class="flex items-center gap-1"><ChevronRight size={14} />{entry.input}</span>
						</div>
						<div class="mt-1 pl-[4.6rem] font-sans">
							{#if entry.error}
								<div class="text-sm text-red-400">{entry.error}</div>
							{:else if entry.result}
								{#if !entry.result.embed && entry.result.lines.length === 0}
									<div class="text-xs text-zinc-500">Done.</div>
								{:else}
									<EmbedView result={entry.result} />
								{/if}
							{:else}
								<div class="text-xs text-zinc-500">Running…</div>
							{/if}
						</div>
					</div>
				{/if}
			{/each}
		</div>
		{#if !atBottom}
			<button
				class="btn-secondary absolute right-4 bottom-3 rounded-full px-3 py-1.5 text-xs shadow-lg"
				onclick={() => {
					atBottom = true;
					scrollToBottom();
				}}
			>
				<ArrowDown size={14} /> Latest
			</button>
		{/if}
	</div>

	{#if mode === 'chat' ? canChat : canCommand}
		<form onsubmit={submit} class="flex items-center gap-2 border-t border-zinc-800 p-2">
			<input
				class="input border-transparent bg-zinc-950 font-mono"
				bind:value={input}
				onkeydown={onKey}
				placeholder={!running ? 'Connect first' : mode === 'chat' ? 'Send a chat message' : 'Run a command, e.g. status or help'}
				disabled={!running}
				maxlength={mode === 'chat' ? 256 : 1000}
				autocomplete="off"
				autocapitalize="off"
				spellcheck="false"
			/>
			<button class="btn-primary shrink-0 px-3" disabled={!canSend || !input.trim() || sending} aria-label="Send">
				<SendHorizontal size={16} />
			</button>
		</form>
	{/if}
</div>
