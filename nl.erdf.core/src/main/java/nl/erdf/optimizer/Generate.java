package nl.erdf.optimizer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Random;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.concurrent.ExecutorService;

import nl.erdf.constraints.Constraint;
import nl.erdf.constraints.impl.StatementPatternConstraint;
import nl.erdf.constraints.impl.StatementPatternSetConstraint;
import nl.erdf.datalayer.DataLayer;
import nl.erdf.model.Request;
import nl.erdf.model.ResourceProvider;
import nl.erdf.model.Solution;
import nl.erdf.model.Triple;
import nl.erdf.model.Variable;
import nl.erdf.util.Convert;
import nl.erdf.util.Randomizer;

import org.openrdf.model.Resource;
import org.openrdf.model.URI;
import org.openrdf.model.Value;
import org.openrdf.query.algebra.StatementPattern;
import org.openrdf.query.algebra.Var;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @author tolgam
 * 
 */
public class Generate {
	/** Logger */
	final Logger logger = LoggerFactory.getLogger(Generate.class);

	// Data layer
	private final DataLayer dataLayer;

	// Request
	private final Request request;

	// Parallel executor to speed stuff up
	protected ExecutorService executor;

	// Roulette for picking up a variable to change
	private Roulette rouletteVariable = null;

	/**
	 * @param dataLayer
	 * @param request
	 *            the request to solve
	 * @param executor
	 */
	public Generate(DataLayer dataLayer, Request request, ExecutorService executor) {
		this.dataLayer = dataLayer;
		this.request = request;
		this.executor = executor;
	}

	/**
	 * Create a set of new candidate solutions. The set created is duplicate
	 * free and a duplicate count is set for all individuals
	 * 
	 * @param population
	 * @param target
	 * 
	 */
	public void createPopulation(final SortedSet<Solution> population, final Set<Solution> target) {
		// Prepare a roulette with the parents
		Roulette parents = new Roulette();
		for (Solution parent : population)
			parents.add(parent, parent.getFitness());
		parents.prepare();

		// Enforce the values of some variable using the value of some
		// others
		for (int i = 0; i < 7 * population.size(); i++) {
			// Pick a parent
			Solution parent = (Solution) parents.nextElement();

			// Generate a child
			Solution child = enforce(parent);

			// Add the new individual
			if (!target.contains(child))
				target.add(child);

		}

		// Do some crossover for higher randomization
		/*
		 * for (int i = 0; i < 2 * population.size(); i++) { // Pick a parent
		 * Solution parent1 = (Solution) parents.nextElement();
		 * 
		 * // Pick a second parent Solution parent2 = parent1; while
		 * (parent2.equals(parent1)) parent2 = (Solution) parents.nextElement();
		 * 
		 * // Generate a child Solution child = crossover(parent1, parent2);
		 * 
		 * // Add the new individual if (!target.contains(child))
		 * target.add(child);
		 * 
		 * }
		 */

		logger.info("Created " + target.size() + " new individuals from " + population.size() + " parents");
	}

	/**
	 * @param population
	 * @param target
	 */
	private Solution enforce(Solution parent) {
		// Keep track of changed values
		Set<String> changed = new HashSet<String>();

		// Clone the parent
		Solution child = parent.clone();

		// Build a roulette for the variable to change
		// we give higher chances to the most constrained variables
		if (rouletteVariable == null) {
			rouletteVariable = new Roulette();
			TreeSet<Pair> list = new TreeSet<Pair>();
			for (Variable variable : child.getVariables()) {
				int nbProviders = request.getResourceProvidersFor(variable.getName()).size();
				if (nbProviders > 0)
					list.add(new Pair(variable.getName(), nbProviders));
			}

			int i = 1;
			for (Pair pair : list) {
				rouletteVariable.add(pair.getKey(), i);
				i++;
			}
			rouletteVariable.prepare();
		}
		String variableName = (String) rouletteVariable.nextElement();
		//logger.info("Change " + variableName);

		// Build a roulette for the provider to use
		// we give higher chances to the providers with low cardinality
		// TODO Sort based on size and assign fixed scores to get rid of
		// constant value
		// list.clear();
		Roulette rouletteProvider = new Roulette();
		for (ResourceProvider provider : request.getResourceProvidersFor(variableName)) {
			long nbResources = provider.getNumberResources(variableName, child, dataLayer);
			if (nbResources > 0)
				rouletteProvider.add(provider, 1.0 / (1.0 + (nbResources / 10000.0)));
		}
		rouletteProvider.prepare();

		if (!rouletteProvider.isEmpty()) {
			ResourceProvider provider = (ResourceProvider) rouletteProvider.nextElement();

			// Get a new value
			Value v = provider.getResource(variableName, child, dataLayer);
			child.getVariable(variableName).setValue(v);
			// logger.info("Assign " + v + " to " + variableName);

			// Add this variable to the changed variables
			changed.add(variableName);

			// Do cascading changes
			propagateChange(child, variableName, changed);
		}

		return child;
	}

	/**
	 * @param variable
	 * @param changed
	 */
	private void propagateChange(Solution child, String variable, Set<String> changed) {
		// Iterate over the constraints
		for (Constraint cstr : request.getConstraintsFor(variable)) {
			// Create a list of triple patterns that can be use to propagate a
			// new value to some variable
			List<StatementPattern> patterns = new ArrayList<StatementPattern>();
			if (cstr instanceof StatementPatternConstraint)
				patterns.add(((StatementPatternConstraint) cstr).getPattern());
			if (cstr instanceof StatementPatternSetConstraint)
				for (StatementPatternConstraint cstr2 : ((StatementPatternSetConstraint) cstr).getPatternConstraints())
					patterns.add(cstr2.getPattern());

			// Make a list of variables that can be changed and the associated
			// set of patterns to use
			Map<String, Set<StatementPattern>> map = new HashMap<String, Set<StatementPattern>>();
			for (StatementPattern pattern : patterns) {
				Set<String> vars = new HashSet<String>();
				for (Var var : pattern.getVarList())
					if (!var.hasValue())
						vars.add(var.getName());
				if (vars.remove(variable) && vars.size() == 1) {
					String otherVariable = (String) vars.toArray()[0];
					if (!changed.contains(otherVariable)) {
						if (!map.containsKey(otherVariable)) {
							Set<StatementPattern> set = new HashSet<StatementPattern>();
							set.add(pattern);
							map.put(otherVariable, set);
						} else {
							map.get(otherVariable).add(pattern);
						}
					}
				}
			}

			// Go over the entries of (key, patterns) to cascade some changes
			for (Entry<String, Set<StatementPattern>> entry : map.entrySet()) {
				// Build a list of possible values, allow for duplicates to give
				// more chances for resources more represented
				ArrayList<Value> values = new ArrayList<Value>();

				for (StatementPattern pattern : entry.getValue()) {
					// Get the instantiated triple and keep the target variable
					// as a null
					Value s = Convert.getValue(pattern.getSubjectVar(), child);
					if (pattern.getSubjectVar().getName().equals(entry.getKey()))
						s = null;
					Value p = Convert.getValue(pattern.getPredicateVar(), child);
					if (pattern.getPredicateVar().getName().equals(entry.getKey()))
						p = null;
					Value o = Convert.getValue(pattern.getObjectVar(), child);
					if (pattern.getObjectVar().getName().equals(entry.getKey()))
						o = null;

					if (s instanceof Resource && p instanceof URI) {
						Triple t = new Triple((Resource) s, (URI) p, o);

						// Get a value
						Value value = dataLayer.getResource(t);
						values.add(value);
					}
				}

				Random rand = Randomizer.instance();
				if (!values.isEmpty()) {
					// Assign one of the new value
					Value value = values.get(rand.nextInt(values.size()));
					child.getVariable(entry.getKey()).setValue(value);

				} else {
					child.getVariable(entry.getKey()).setValue(null);
				}

				// See what can be changed from here
				changed.add(entry.getKey());
				propagateChange(child, entry.getKey(), changed);
			}
		}
	}

	/**
	 * @param population
	 * @param target
	 */
	protected Solution crossover(Solution parentA, Solution parentB) {
		Solution child = parentA.clone();
		for (Variable variable : child.getVariables()) {
			double firstR = parentA.getVariable(variable.getName()).getReward();
			double secondR = parentB.getVariable(variable.getName()).getReward();
			if (secondR > firstR)
				child.getVariable(variable.getName()).setValue(parentB.getVariable(variable.getName()).getValue());
		}

		return child;
	}
}
