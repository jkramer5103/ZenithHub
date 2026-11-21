import { getContext, setContext } from 'svelte';
import type { Capability, FlowStep, Pearl } from '#lib/types.js';

/** What every block of the open flow can reach. */
export interface EditorContext {
	capabilities: Capability[];
	waypoints: string[];
	pearls: Pearl[];
	/** the other bots this user controls, for "broadcast to" */
	bots: { id: string; name: string }[];
	/** the running block, and the one the last run failed on */
	live: { step?: string; error?: string; message?: string };
	/** opens the block picker; the block goes into list at index */
	pick: (list: FlowStep[], index: number) => void;
	/** the text field {player} chips type into */
	focusText: (target: { obj: Record<string, unknown>; key: string } | null) => void;
	/** drag and drop (desktop): the block being dragged, and where it may land */
	drag: { list: FlowStep[]; index: number; step: FlowStep } | null;
	startDrag: (list: FlowStep[], index: number) => void;
	endDrag: () => void;
	canDrop: (list: FlowStep[]) => boolean;
	drop: (list: FlowStep[], index: number) => void;
}

const KEY = Symbol('flow-editor');
export const provideEditor = (ctx: EditorContext) => setContext(KEY, ctx);
export const editor = () => getContext<EditorContext>(KEY);

/** an input inside a block: a dark pill on the block's colour */
export const SLOT =
	'rounded-md border-0 bg-black/25 px-2 py-0.5 text-sm font-normal text-white placeholder-white/50 outline-none focus:bg-black/40 focus:ring-2 focus:ring-white/60';
export const SELECT = SLOT + ' field-sizing-content max-w-full min-w-0 cursor-pointer pr-1 [&>option]:bg-zinc-900 [&>optgroup]:bg-zinc-900';
