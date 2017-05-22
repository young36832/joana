/**
 * This file is part of the Joana IFC project. It is developed at the
 * Programming Paradigms Group of the Karlsruhe Institute of Technology.
 *
 * For further details on licensing please read the information at
 * http://joana.ipd.kit.edu or contact the authors.
 */
package edu.kit.joana.wala.core.graphs;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.TreeSet;

import org.jgrapht.DirectedGraph;
import org.jgrapht.EdgeFactory;

import com.google.common.collect.Ordering;

import edu.kit.joana.util.Pair;
import edu.kit.joana.util.graph.AbstractJoanaGraph;
import edu.kit.joana.util.graph.Graphs;
import edu.kit.joana.util.graph.KnowsVertices;
import edu.kit.joana.util.graph.TarjanStrongConnectivityInspector;
import edu.kit.joana.wala.core.graphs.NTSCDGraph.MaxPaths;

import static edu.kit.joana.wala.core.graphs.NTSCDGraph.condNodes;
import static edu.kit.joana.wala.core.graphs.NTSCDGraph.selfRef;
import static edu.kit.joana.wala.core.graphs.NTSCDGraph.maxPaths;
import static edu.kit.joana.wala.core.graphs.NTSCDGraph.add;
import static edu.kit.joana.wala.core.graphs.NTSCDGraph.get;
import static edu.kit.joana.wala.core.graphs.NTSCDGraph.set;
/**
 * Computes nontermination insensitive control dependence.
 * 
 * @author Martin Hecker  <martin.hecker@kit.edu>
 *
 */
public class NTICDGraphLeastFPDualWorklistSymbolic<V, E extends KnowsVertices<V>> extends AbstractJoanaGraph<V, E> {

	private static class WorkbagItem<V> {
		private V m,n,x;

		public WorkbagItem(V m, V n, V x) {
			this.m = m; this.n = n; this.x = x;
		}
		@Override
		public boolean equals(Object obj) {
			@SuppressWarnings("rawtypes")
			final WorkbagItem that = (WorkbagItem) obj;
			return this.m == that.m && this.n == that.n && this.x == that.x;
		}

		@Override
		public int hashCode() {
			final int prime = 31;
			int result = 1;
			result = prime * result + m.hashCode();
			result = prime * result + n.hashCode();
			result = prime * result + x.hashCode();
			return result;
		}

		
	}
	
	private static class NumberedReachabilityWorkbagItem<V> {
		private final Pair<V,V> px;
		private final Set<V> rs;
		private final int sccIndex;
		private final int xIndex;
		
		public NumberedReachabilityWorkbagItem(Pair<V,V> px, Set<V> rs, int sccIndex, int xIndex) {
			this.px = px; this.rs = rs;
			this.sccIndex = sccIndex;
			this.xIndex = xIndex;
		}
		
		@Override
		public boolean equals(Object obj) {
			@SuppressWarnings("rawtypes")
			final NumberedReachabilityWorkbagItem that = (NumberedReachabilityWorkbagItem) obj;
			return this.px == that.px && this.rs == that.rs;
		}

		@Override
		public int hashCode() {
			final int prime = 31;
			int result = 1;
			result = prime * result + System.identityHashCode(px);
			result = prime * result + System.identityHashCode(rs);
			return result;
		}
		
		/* (non-Javadoc)
		 * @see java.lang.Object#toString()
		 */
		@Override
		public String toString() {
			return px.toString() + ": " + rs.toString();
		}
	}
	

	private NTICDGraphLeastFPDualWorklistSymbolic(EdgeFactory<V, E> edgeFactory) {
		super(edgeFactory);
	}

	public static boolean DEBUG = false;

	/**
	 * Computes nontermination insensitive control dependence.
	 * The pseudo code in "A New Foundation for Control Dependence and Slicing
	 * for Modern Program Structures" from Ranganath, Amtoft, Banerjee and Hatcliff
	 * is flawed.
	 * 
	 * This Algorithm fixes theirs, and is not flawed.
	 * It works by computing the *least* fixed point of the dual f^C of the functional f used in {@link NTICDGraphGreatestFPWorklistSymbolic}.
	 * 
	 */
	public static <V , E extends KnowsVertices<V>> NTICDGraphLeastFPDualWorklistSymbolic<V, E> compute(DirectedGraph<V, E> cfg, EdgeFactory<V, E> edgeFactory) {
		NTICDGraphLeastFPDualWorklistSymbolic<V, E> cdg = new NTICDGraphLeastFPDualWorklistSymbolic<>(edgeFactory);
		for (V n : cfg.vertexSet()) {
			cdg.addVertex(n);
		}

		final Instant startAll = Instant.now();
		final Instant startCondSelfRef = startAll;
		//# (1) Initialize
		Set<V> condNodes = condNodes(cfg);
 		Set<V> selfRef = selfRef(cfg);
 		final Instant stopCondSelfRef = Instant.now();
 		
		final Instant startSuccNodes = stopCondSelfRef;
		if (DEBUG) {
			System.out.print("\n\n\n\n"); 
			System.out.print("cond nodes: ");
			for (V n : condNodes) {
				System.out.print(n + "; ");
			}
			System.out.println();

			System.out.print("self ref: ");
			for (V n : selfRef) {
				System.out.print(n + "; ");
			}
			System.out.println();
		}

		//DEBUG = cdg.toString().contains("1|ENTR|joana.api.testdata.toy.rec.MyList.main(java.lang.String[])");
		final Map<V, V>      nextCond  = new HashMap<>();
		final Map<V, Set<V>> toNextCond = new HashMap<>();
		final Map<V, Set<Pair<V,V>>> prevCondsWithSucc = new HashMap<>();
		
		final Map<V, Set<V>> represents = new HashMap<>();
		Map<V, V> representantOf = new HashMap<>();
		
		final Map<V, Set<V>> succNodesOf = new HashMap<>(condNodes.size());
		
		final Map<V, Map<V, Set<MaxPaths<V>>>> S = new HashMap<>();

		
		for (final V p : condNodes) {
			prevCondsWithSucc.put(p, new HashSet<>());
		}
		
		
		for (final V p : condNodes) {
			succNodesOf.put(p, Graphs.getSuccNodes(cfg, p));
		}
		final Instant stopSuccNodes = Instant.now();
		
		final Instant startCompNextCondPrevConds = stopSuccNodes;
		for (final V n : condNodes) {
			for (V m : succNodesOf.get(n)) {
				{ // nextCond, prevConds
					final Set<V> seen = new HashSet<>();
					V current = m;
					V next;
					Set<V> succNodes; 
					while ((succNodes = Graphs.getSuccNodes(cfg, current)).size() == 1 &&
					       !seen.contains((next = succNodes.iterator().next()))
					) {
						seen.add(current);
						current = next;
					}
					
					seen.add(current);
					toNextCond.put(m,seen);
					
					if (succNodes.size() > 1) {
						if (!condNodes.contains(current)) throw new IllegalStateException();
						nextCond.put(m, current);
						
						prevCondsWithSucc.compute(current, (c, ps) -> {
							if (ps == null) {
								final Set<Pair<V,V>> prevs = new HashSet<>();
								prevs.add(Pair.pair(n,m));
								return prevs;
							} else {
								ps.add(Pair.pair(n,m));
								return ps;
							}
						});
					}
				}
			}
		}
		final Instant stopCompNextCondPrevConds = Instant.now();
		
		final Instant startRepresentants = stopCompNextCondPrevConds;
		for (V start : cfg.vertexSet()){ // representantOf
			final Collection<V> sameRepresentant = new LinkedList<>();
			V m = start;
			V n = start;
			V representant = representantOf.get(n);
			final boolean newRepresentant = representant == null;
			
			if (newRepresentant) {
				if (condNodes.contains(start)) {
					final Set<V> preds = Graphs.getPredNodes(cfg, start);
					if (preds.size() != 1) {
						representant = start;
						sameRepresentant.add(start);
					} else {
						n = preds.iterator().next();
					}
				}
			}
			
			while (representant == null) {
				sameRepresentant.add(m);
				final Set<V> succs = Graphs.getSuccNodes(cfg,n);
				if (succs.size() == 1) {
					final Set<V> preds = Graphs.getPredNodes(cfg,n);
					if (preds.size() == 1) {
						m = n;
						n = preds.iterator().next();
					} else {
						representant = n;
					}
				} else {
					representant = m;
				}
			}
			if (newRepresentant) {
				represents.compute(representant, (r, reps) -> {
					if (reps == null) {
						reps = new HashSet<>(sameRepresentant);
						return reps;
					} else {
						reps.addAll(sameRepresentant);
					}
					return reps;
				});
				for (V s : sameRepresentant) {
					representantOf.put(s, representant);
				}
			}
		}
		representantOf = null;
		// This set is iterated over repeatedly, so we allocate an array for which this iteration is marginally faster.
		@SuppressWarnings("unchecked")
		V[] representants = (V[]) new Object[represents.keySet().size()]; {
			int i = 0;
			for (V r : represents.keySet()) {
				representants[i++] = r;
			}
		}
		final Instant stopRepresentants = Instant.now();

		
		final Instant startInitialize = stopRepresentants;
		for (V p : condNodes) {
			for (V m : representants) {
				set(S, m, p, new HashSet<>());
			}
		}
		final Map<Pair<V,V>, Set<V>> rreachable = new HashMap<>();
		
		final TarjanStrongConnectivityInspector<V, E> sccs = new TarjanStrongConnectivityInspector<V, E>(cfg);
		final Map<V, TarjanStrongConnectivityInspector.VertexNumber<V>> indices = sccs.getVertexToVertexNumber();
		
		// order the workQueue by the reverse topological sorting implicit in the Tarjan SCC computation.
		final Set<NumberedReachabilityWorkbagItem<V>> workQueue = new TreeSet<>(new Comparator<NumberedReachabilityWorkbagItem<V>>() {
			final Ordering<Object> fallbackOrdering = Ordering.arbitrary();
			@Override
			public int compare(NumberedReachabilityWorkbagItem<V> o1, NumberedReachabilityWorkbagItem<V> o2) {
				final int sccCompare = Integer.compare(o1.sccIndex, o2.sccIndex); 
				if (sccCompare != 0) return sccCompare;
				
				final int xIndexCompare = Integer.compare(o1.xIndex, o2.xIndex);
				if (xIndexCompare != 0) return -xIndexCompare;
				
				final int pxCompare = Integer.compare(System.identityHashCode(o1.px), System.identityHashCode(o2.px)); 
				if (pxCompare != 0) return pxCompare;
				
				final int rsCompare = Integer.compare(System.identityHashCode(o1.rs), System.identityHashCode(o2.rs));
				if (rsCompare != 0) return rsCompare;
				
				return fallbackOrdering.compare(o1.rs, o2.rs);
			}
		});
		
		for (final V p : condNodes) {
			for (V x : succNodesOf.get(p)) {
				final Set<V> rs = new HashSet<>(); 
				for (V m : toNextCond.get(x)) {
					if (represents.containsKey(m)) {
						rs.add(m);
					}
				}
				rreachable.put(Pair.pair(p, x), rs);
				for (Pair<V,V> ny : prevCondsWithSucc.get(p)) {
					final V y = ny.getSecond();
					final TarjanStrongConnectivityInspector.VertexNumber<V> yNumber = indices.get(y); 
					final NumberedReachabilityWorkbagItem<V> workBagItem =
						new NumberedReachabilityWorkbagItem<V>(
							ny,
							rs,
							yNumber.getSccNumber(),
							yNumber.getNumber()
						);
					workQueue.add(workBagItem);
				}
			}
		}
		while (!workQueue.isEmpty()) {
			final Pair<V,V> px;
			final V p;
			final Set<V> newRs;
			{
				final Iterator<NumberedReachabilityWorkbagItem<V>> iterator = workQueue.iterator();
				final NumberedReachabilityWorkbagItem<V> e = iterator.next();
				iterator.remove();
				
				px = e.px;
				p = px.getFirst();
				newRs = e.rs;
			}
			final Set<V> rs = rreachable.get(px);
			if (rs.addAll(newRs)) {
				for (Pair<V,V> ny : prevCondsWithSucc.get(p)) {
					final V y = ny.getSecond();
					final TarjanStrongConnectivityInspector.VertexNumber<V> yNumber = indices.get(y);
					final NumberedReachabilityWorkbagItem<V> workBagItem =
						new NumberedReachabilityWorkbagItem<V>(
							ny,
							rs,
							yNumber.getSccNumber(),
							yNumber.getNumber()
						);
					workQueue.add(workBagItem);
				}
			}
		}

		
		
		
		for (Entry<Pair<V,V>, Set<V>> entry : rreachable.entrySet()) {
			final Pair<V,V> px = entry.getKey();
			final V p = px.getFirst();
			final V x = px.getSecond();
			final Set<V> reachableFromX = entry.getValue();
			for (V m : representants) {
				if (!reachableFromX.contains(m)) {
					add(S, m, p, maxPaths(cdg,p,x));
				}
			}
		}
		final Instant stopInitialize = Instant.now();
		
		final Instant startInitializeWorkbag = stopInitialize;
		final LinkedHashSet<WorkbagItem<V>> workbag = new LinkedHashSet<>();
		
		for (Entry<V, Map<V, Set<MaxPaths<V>>>> eOuter : S.entrySet()) {
			final V m = eOuter.getKey();
			for (Entry<V, Set<MaxPaths<V>>> eInner : eOuter.getValue().entrySet()) {
				final V p = eInner.getKey();
				final Set<MaxPaths<V>> smp = eInner.getValue();
				if (!smp.isEmpty()) {
					for (Pair<V,V> nx : prevCondsWithSucc.get(p)) {
						workbag.add(new WorkbagItem<>(m,nx.getFirst(),nx.getSecond()));
					}
				}
			}
		}
		final Instant stopInitializeWorkbag = Instant.now();
		
		final Instant startIteration = stopInitializeWorkbag;
		int iterations = 0;
		while (!workbag.isEmpty()) {
			iterations ++;
			final V m;
			final V p;
			final V x;
			{
				final Iterator<WorkbagItem<V>> iterator = workbag.iterator();
				final WorkbagItem<V> next = iterator.next();
				iterator.remove();
				
				m = next.m;
				p = next.n;
				x = next.x;
			}
			
			final Set<MaxPaths<V>> smp = get(S, m, p);
			
			boolean changed = false;
			if (!toNextCond.get(x).contains(m)) {
				changed = smp.add(maxPaths(cfg,p,x));
			}
			if (changed && smp.size() == 1) {
				for (Pair<V,V> nx_ : prevCondsWithSucc.get(p)) {
					workbag.add(new WorkbagItem<V>(m, nx_.getFirst(), nx_.getSecond()));
				}
			}
		}
		final Instant stopIteration = Instant.now(); 
		
		final Instant startPostprocessing1 = stopIteration;
		final Instant stopPostprocessing1 = stopIteration;
		
		final Instant startPostprocessing2 = stopIteration;
		if (DEBUG && iterations > 200000) {
			System.out.println("LFP: Iterations (" + cfg.toString() +  "): " + iterations);
		}
		
		
		//# (3) Calculate non-termination insensitive control dependence
		for (Entry<V, Set<V>> e : represents.entrySet()) {
			for (V m : condNodes) {
				final Set<MaxPaths<V>> Snm = get(S, e.getKey(), m);
				final int Tm = succNodesOf.get(m).size();
				
				assert Snm.size() <= Tm;
				if (Snm.size() > 0 && Snm.size() < Tm) {
					for (V n : e.getValue()) {
						if (n == m) {
							continue;
						}
						cdg.addEdge(m, n);
						if (DEBUG) {
							System.out.print("S(" + n + ", " + m + ") = {");
							for (MaxPaths<V> t_nm : Snm) {
								System.out.print(t_nm + "; ");
							}
							System.out.println("}");
						}
					}
				}

			}
		}
		final Instant stopPostprocessing2 = Instant.now();
		
		final Instant stopAll = stopPostprocessing2;
		if (DEBUG) {
			final Duration durationCondSelfRef =
					Duration.between(
						startCondSelfRef,
						stopCondSelfRef
					);
			final Duration durationCompReachableNextCondPrevConds =
				Duration.between(
					startCompNextCondPrevConds,
					stopCompNextCondPrevConds
				);
			final Duration durationSuccNodes =
					Duration.between(
						startSuccNodes,
						stopSuccNodes
					);
			final Duration durationInitializeWorkbag =
					Duration.between(
						startInitializeWorkbag,
						stopInitializeWorkbag
					);
			final Duration durationRepresentants =
					Duration.between(
						startRepresentants,
						stopRepresentants
					);
			final Duration durationInitialize =
					Duration.between(
						startInitialize,
						stopInitialize
					);
			final Duration durationIteration =
					Duration.between(
						startIteration,
						stopIteration
					);
			
			final Duration durationPostprocessing1 =
					Duration.between(
						startPostprocessing1,
						stopPostprocessing1
					);
			
			final Duration durationPostprocessing2 =
					Duration.between(
						startPostprocessing2,
						stopPostprocessing2
					);			
			final Duration durationTotal =
					Duration.between(
						startAll,
						stopAll
					);
			
			@SuppressWarnings("unchecked")
			Pair<String,Duration>[] durations =  (Pair<String,Duration>[]) new Pair[] {
				Pair.pair("SuccNodes", durationSuccNodes),
				Pair.pair("CondSelf", durationCondSelfRef),
				Pair.pair("NextCondPrevCond", durationCompReachableNextCondPrevConds),
				Pair.pair("Representants", durationRepresentants),
				Pair.pair("Initial", durationInitialize),
				Pair.pair("InitializeWorkbag", durationInitializeWorkbag),
				Pair.pair("Iteration", durationIteration),
				Pair.pair("Postprocessing", durationPostprocessing1),
				Pair.pair("Postprocessing", durationPostprocessing2),
			};
			
			final long total = durationTotal.toNanos();
			if (total >= 10000000) {
				for (Pair<String,Duration> duration : durations){
					System.out.print(
						duration.getFirst() + ":\t"  + 
	//					duration.getSecond().toNanos() + "ns "+ 
						(100*duration.getSecond().toNanos())/total + "%\t\t"
					);
				}
				System.out.println("Total:\t" + total + "ns");
			}
		}
		return cdg;
	}
}