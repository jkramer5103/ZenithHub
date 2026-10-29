<script lang="ts">
	let {
		checked,
		disabled = false,
		label,
		onchange
	}: { checked: boolean; disabled?: boolean; label: string; onchange: (checked: boolean) => void } = $props();
</script>

<button
	type="button"
	role="switch"
	aria-checked={checked}
	aria-label={label}
	{disabled}
	class="relative inline-flex h-6 w-11 shrink-0 items-center rounded-full transition-colors focus-visible:ring-2 focus-visible:ring-emerald-500 focus-visible:outline-none disabled:cursor-not-allowed disabled:opacity-50 {checked
		? 'bg-emerald-600'
		: 'bg-zinc-700'}"
	onclick={() => onchange(!checked)}
>
	<span class="inline-block size-5 rounded-full bg-white shadow transition-transform {checked ? 'translate-x-5.5' : 'translate-x-0.5'}"></span>
</button>
