package crypto.analysis;

import java.util.Collection;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import com.google.common.base.Stopwatch;
import com.google.common.collect.Lists;
import com.google.inject.internal.util.Sets;

import boomerang.BackwardQuery;
import boomerang.Boomerang;
import boomerang.ForwardQuery;
import boomerang.Query;
import boomerang.debugger.Debugger;
import boomerang.jimple.Statement;
import boomerang.jimple.Val;
import boomerang.results.BackwardBoomerangResults;
import crypto.Utils;
import crypto.boomerang.CogniCryptIntAndStringBoomerangOptions;
import crypto.extractparameter.CallSiteWithParamIndex;
import crypto.extractparameter.ExtractParameterAnalysis;
import crypto.predicates.PredicateHandler;
import crypto.rules.CryptSLRule;
import crypto.typestate.CryptSLMethodToSootMethod;
import heros.utilities.DefaultValueMap;
import ideal.IDEALSeedSolver;
import soot.MethodOrMethodContext;
import soot.Scene;
import soot.SootMethod;
import soot.Type;
import soot.Unit;
import soot.jimple.Stmt;
import soot.jimple.toolkits.callgraph.ReachableMethods;
import soot.jimple.toolkits.ide.icfg.BiDiInterproceduralCFG;
import soot.util.queue.QueueReader;
import sync.pds.solver.nodes.Node;
import typestate.TransitionFunction;
import wpds.impl.Weight.NoWeight;

public abstract class CryptoScanner {

	public static boolean APPLICATION_CLASS_SEEDS_ONLY = false;
	private final LinkedList<IAnalysisSeed> worklist = Lists.newLinkedList();
	private final List<ClassSpecification> specifications = Lists.newLinkedList();
	private final PredicateHandler predicateHandler = new PredicateHandler(this);
	private CrySLResultsReporter resultsAggregator = new CrySLResultsReporter();

	private DefaultValueMap<Node<Statement, Val>, AnalysisSeedWithEnsuredPredicate> seedsWithoutSpec = new DefaultValueMap<Node<Statement, Val>, AnalysisSeedWithEnsuredPredicate>() {

		@Override
		protected AnalysisSeedWithEnsuredPredicate createItem(Node<Statement, Val> key) {
			return new AnalysisSeedWithEnsuredPredicate(CryptoScanner.this, key);
		}
	};
	private DefaultValueMap<AnalysisSeedWithSpecification, AnalysisSeedWithSpecification> seedsWithSpec = new DefaultValueMap<AnalysisSeedWithSpecification, AnalysisSeedWithSpecification>() {

		@Override
		protected AnalysisSeedWithSpecification createItem(AnalysisSeedWithSpecification key) {
			return new AnalysisSeedWithSpecification(CryptoScanner.this, key.stmt(), key.var(), key.getSpec());
		}
	};
	private int solvedObject;
	private Stopwatch analysisWatch;
	private Set<Node<Statement,Val>> boomerangQueries  = Sets.newHashSet();

	public abstract BiDiInterproceduralCFG<Unit, SootMethod> icfg();

	public CrySLResultsReporter getAnalysisListener() {
		return resultsAggregator;
	};

	public abstract boolean isCommandLineMode();

	public abstract boolean rulesInSrcFormat();

	public CryptoScanner() {
		CryptSLMethodToSootMethod.reset();
	}

	public void scan(List<CryptSLRule> specs) {
		for (CryptSLRule rule : specs) {
			specifications.add(new ClassSpecification(rule, this));
		}
		CrySLResultsReporter listener = getAnalysisListener();
		listener.beforeAnalysis();
		analysisWatch = Stopwatch.createStarted();
		System.out.println("Searching fo Seeds for analysis!");
		initialize();
		long elapsed = analysisWatch.elapsed(TimeUnit.SECONDS);
		System.out.println("Discovered " + worklist.size() + " analysis seeds within " + elapsed + " seconds!");
		while (!worklist.isEmpty()) {
			IAnalysisSeed curr = worklist.poll();
			listener.discoveredSeed(curr);
			curr.execute();
			estimateAnalysisTime();
		}

		checkPredicates();

		for (AnalysisSeedWithSpecification seed : getAnalysisSeeds()) {
			if (seed.isSecure()) {
				listener.onSecureObjectFound(seed);
			}
		}
		
		listener.afterAnalysis();
		elapsed = analysisWatch.elapsed(TimeUnit.SECONDS);
		System.out.println("Static Analysis took " + elapsed + " seconds!");
//		debugger().afterAnalysis();
	}

	public void addSeed(AnalysisSeedWithEnsuredPredicate value) {
		seedsWithoutSpec.put(value.asNode(), value);
	}
	private void checkPredicates() {
		System.out.println("===== COMBINE FLOWS RELATION ====== ");
		for(AnalysisSeedWithSpecification seed : getAnalysisSeeds()) {
			seed.getParameterAnalysis().combineDataFlowsForRuleObjects();
			getAnalysisListener().collectedValues(seed, seed.getParameterAnalysis().getCollectedValues());
		}
		System.out.println("===== EVALUTE INTERNAL CONSTRAINTS AND GENERATE REQUIRED PREDICATES ====== ");
		for(AnalysisSeedWithSpecification seed : getAnalysisSeeds()) {
			seed.evaluateInternalConstraints();
		}
		System.out.println("===== FIX POINT FOR ENSURES ====== ");
		checkMissingRequiredPredicates();
	}

	private void checkMissingRequiredPredicates() {
		boolean hasPredicateRemoved = true;
		while(hasPredicateRemoved) {
			hasPredicateRemoved = false;
			for(AnalysisSeedWithSpecification seed : this.seedsWithSpec.values()) {
				hasPredicateRemoved |= seed.checkPredicates();
			}
		}
	}

	
	
	private void estimateAnalysisTime() {
		int remaining = worklist.size();
		solvedObject++;
		if (remaining != 0) {
//			Duration elapsed = analysisWatch.elapsed();
//			Duration estimate = elapsed.dividedBy(solvedObject);
//			Duration remainingTime = estimate.multipliedBy(remaining);
//			System.out.println(String.format("Analysis Time: %s", elapsed));
//			System.out.println(String.format("Estimated Time: %s", remainingTime));
			System.out.println(String.format("Analyzed Objects: %s of %s", solvedObject, remaining + solvedObject));
			System.out.println(String.format("Percentage Completed: %s\n",
					((float) Math.round((float) solvedObject * 100 / (remaining + solvedObject))) / 100));
		}
	}

	private void initialize() {
		ReachableMethods rm = Scene.v().getReachableMethods();
		QueueReader<MethodOrMethodContext> listener = rm.listener();
		while (listener.hasNext()) {
			MethodOrMethodContext next = listener.next();
			SootMethod method = next.method();
			if (method == null || !method.hasActiveBody()) {
				continue;
			}
			for (ClassSpecification spec : getClassSpecifictions()) {
				spec.invokesForbiddenMethod(method);
				if (spec.getRule().getClassName().equals("javax.crypto.SecretKey")) {
					continue;
				}
				for (Query seed : spec.getInitialSeeds(method)) {
					getOrCreateSeedWithSpec(new AnalysisSeedWithSpecification(this, seed.stmt(), seed.var(), spec));
				}
			}
		}
	}

	public List<ClassSpecification> getClassSpecifictions() {
		return specifications;
	}

	protected void addToWorkList(IAnalysisSeed analysisSeedWithSpecification) {
		worklist.add(analysisSeedWithSpecification);
	}

	public AnalysisSeedWithEnsuredPredicate getOrCreateSeed(Node<Statement,Val> factAtStatement) {
		boolean addToWorklist = false;
		if (!seedsWithoutSpec.containsKey(factAtStatement))
			addToWorklist = true;

		AnalysisSeedWithEnsuredPredicate seed = seedsWithoutSpec.getOrCreate(factAtStatement);
		if (addToWorklist)
			addToWorkList(seed);
		return seed;
	}

	public AnalysisSeedWithSpecification getOrCreateSeedWithSpec(AnalysisSeedWithSpecification factAtStatement) {
		boolean addToWorklist = false;
		if (!seedsWithSpec.containsKey(factAtStatement))
			addToWorklist = true;
		AnalysisSeedWithSpecification seed = seedsWithSpec.getOrCreate(factAtStatement);
		if (addToWorklist)
			addToWorkList(seed);
		return seed;
	}

	public Debugger<TransitionFunction> debugger(IDEALSeedSolver<TransitionFunction> solver,
			IAnalysisSeed analyzedObject) {
		return new Debugger<>();
	}

	public PredicateHandler getPredicateHandler() {
		return predicateHandler;
	}

	public Collection<AnalysisSeedWithSpecification> getAnalysisSeeds() {
		return this.seedsWithSpec.values();
	}

	public boolean hasRulesForType(Type parameterType) {
		List<ClassSpecification> classSpecifictions = getClassSpecifictions();
		for(ClassSpecification spec : classSpecifictions) {
			if(Scene.v().getSootClass(Utils.getFullyQualifiedName(spec.getRule())).getType().equals(parameterType)) {
				return true;
			}
		}
		return false;
	}
	
	public Set<IAnalysisSeed> findSeedsForValAtStatement(Node<Statement,Val> node, boolean triggerQuery){
		Set<IAnalysisSeed> res = Sets.newHashSet();
		if(triggerQuery) {
			res.addAll(triggerBackwardQuery(node));
		}
		for(AnalysisSeedWithEnsuredPredicate seed : seedsWithoutSpec.values()) {
			if(seed.reaches(node)) {
				res.add(seed);
			}
		}
		for(AnalysisSeedWithSpecification seed : seedsWithSpec.values()) {
			if(seed.reaches(node)) {
				res.add(seed);
			}
		}
		return res;
	}

	private Set<IAnalysisSeed> triggerBackwardQuery(Node<Statement, Val> node) {
		Set<IAnalysisSeed> results = Sets.newHashSet();
		if(boomerangQueries.add(node)) {
			for (Unit pred : icfg().getPredsOf(node.stmt().getUnit().get())) {
				Boomerang boomerang = new Boomerang(new CogniCryptIntAndStringBoomerangOptions()) {
					@Override
					public BiDiInterproceduralCFG<Unit, SootMethod> icfg() {
						return CryptoScanner.this.icfg();
					}
				};
				BackwardQuery bwQ = new BackwardQuery(new Statement((Stmt) pred, node.stmt().getMethod()), node.fact());
				BackwardBoomerangResults<NoWeight> res = boomerang.solve(bwQ);
				for(ForwardQuery q : res.getAllocationSites().keySet()) {
					results.addAll(findSeedsFor(q));
					if(results.isEmpty()) {
						AnalysisSeedWithEnsuredPredicate analysisSeedWithEnsuredPredicate = new AnalysisSeedWithEnsuredPredicate(this, q.asNode(), res.asStatementValWeightTable(q));
						addSeed(analysisSeedWithEnsuredPredicate);
					}
				}
			}
		}
		return results;
	}

	public Set<IAnalysisSeed> findSeedsFor(ForwardQuery q) {
		Set<IAnalysisSeed> res = Sets.newHashSet();
		for(AnalysisSeedWithEnsuredPredicate seed : seedsWithoutSpec.values()) {
			if(seed.asNode().equals(q.asNode())) {
				res.add(seed);
			}
		}
		for(AnalysisSeedWithSpecification seed : seedsWithSpec.values()) {
			if(seed.asNode().equals(q.asNode())) {
				res.add(seed);
			}
		}
		return res;
	}
}
