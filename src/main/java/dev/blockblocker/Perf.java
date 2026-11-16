package dev.blockblocker;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Tiny built-in profiler for /block perf: how much main-thread time each part of BlockBlocker uses, and how many
 * packets it sends. Always on (a couple of nanoTime calls per section is far below anything measurable).
 */
final class Perf
{
	static final String CLIENT = "shulkers/fake blocks (every 2 ticks)";
	static final String MOVE = "move checks (server push-back)";
	static final String EDITOR = "custom editor particles";
	static final String OUTLINES = "/block show outlines";
	static final String MOB_MOVES = "mob move checks (every mob step near barriers)";
	static final String MOB_ROUTES = "mob route planning (every tick)";

	private static final class Section
	{
		final AtomicLong nanos = new AtomicLong();
		final AtomicLong calls = new AtomicLong();
		final AtomicLong max = new AtomicLong();
	}

	private static final Map<String, Section> SECTIONS = new LinkedHashMap<>();
	static final AtomicLong SPAWNED = new AtomicLong();
	static final AtomicLong DESTROYED = new AtomicLong();
	static final AtomicLong PARTICLES = new AtomicLong();
	private static long since = System.nanoTime();

	static
	{
		for(String s : new String[] {CLIENT, MOVE, EDITOR, OUTLINES, MOB_MOVES, MOB_ROUTES})
			SECTIONS.put(s, new Section());
	}

	private Perf()
	{
	}

	static void add(String section, long startNanos)
	{
		long d = System.nanoTime() - startNanos;
		Section s = SECTIONS.get(section);
		s.nanos.addAndGet(d);
		s.calls.incrementAndGet();
		s.max.accumulateAndGet(d, Math::max);
	}

	/** Lines describing the time since the last reset, then resets. */
	static java.util.List<String> report()
	{
		java.util.List<String> out = new java.util.ArrayList<>();
		double seconds = Math.max(1e-9, (System.nanoTime() - since) / 1e9);
		double ticks = seconds * 20;
		double total = 0;
		for(Map.Entry<String, Section> e : SECTIONS.entrySet())
		{
			Section s = e.getValue();
			double ms = s.nanos.get() / 1e6;
			total += ms;
			out.add(String.format(java.util.Locale.ROOT, "%s: %.3f ms/tick (max %.2f ms once, %d runs)", e.getKey(), ms / ticks, s.max.get() / 1e6, s.calls.get()));
			s.nanos.set(0);
			s.calls.set(0);
			s.max.set(0);
		}
		out.add(0, String.format(java.util.Locale.ROOT, "Last %.0f s: BlockBlocker used %.3f ms per tick in total (a tick has 50 ms).", seconds, total / ticks));
		out.add(String.format(java.util.Locale.ROOT, "Packets: %.0f shulkers spawned/s, %.0f destroyed/s, %.0f editor lines drawn/s.",
				SPAWNED.getAndSet(0) / seconds, DESTROYED.getAndSet(0) / seconds, PARTICLES.getAndSet(0) / seconds));
		since = System.nanoTime();
		return out;
	}
}
