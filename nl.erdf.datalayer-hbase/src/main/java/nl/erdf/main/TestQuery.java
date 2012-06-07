/**
 * 
 */
package nl.erdf.main;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.Collection;
import java.util.Observable;
import java.util.Observer;

import nl.erdf.constraints.impl.StatementPatternConstraint;
import nl.erdf.datalayer.DataLayer;
import nl.erdf.datalayer.hbase.SpyrosHBaseDataLayer;
import nl.erdf.model.Request;
import nl.erdf.model.Solution;
import nl.erdf.model.Triple;
import nl.erdf.model.impl.StatementPatternProvider;
import nl.erdf.optimizer.Optimizer;

import org.openrdf.model.impl.URIImpl;
import org.openrdf.query.algebra.StatementPattern;
import org.openrdf.query.algebra.Var;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @author Christophe Guéret <christophe.gueret@gmail.com>
 * 
 */
public class TestQuery implements Observer {
	static final Logger logger = LoggerFactory.getLogger(TestQuery.class);
	private final Optimizer optimizer;
	private final Request request;
	private final DataLayer datalayer;

	public TestQuery() throws FileNotFoundException, IOException {
		// Create a data layer
		datalayer = SpyrosHBaseDataLayer.getInstance("default");

		// Create the request
		request = new Request(datalayer);

		// Query :
		// SELECT DISTINCT ?person ?field WHERE {
		// ?person <http://dbpedia.org/ontology/birthPlace>
		// <http://dbpedia.org/resource/Netherlands>.
		// ?person a <http://dbpedia.org/ontology/Artist>.
		// ?person <http://dbpedia.org/ontology/field> ?field.
		// }

		// We want artists
		StatementPattern artistStmt = new StatementPattern();
		artistStmt.setSubjectVar(new Var("person"));
		artistStmt.setPredicateVar(new Var("ar1", new URIImpl("http://www.w3.org/1999/02/22-rdf-syntax-ns#type")));
		artistStmt.setObjectVar(new Var("ar2", new URIImpl("http://dbpedia.org/ontology/Artist")));

		// We want to know their field of work
		StatementPattern fieldStmt = new StatementPattern();
		fieldStmt.setSubjectVar(new Var("person"));
		fieldStmt.setPredicateVar(new Var("f1", new URIImpl("http://dbpedia.org/ontology/field")));
		fieldStmt.setObjectVar(new Var("field"));

		// We filter on birthPlace
		StatementPattern birthPlaceStmt = new StatementPattern();
		birthPlaceStmt.setSubjectVar(new Var("person"));
		birthPlaceStmt.setPredicateVar(new Var("bp1", new URIImpl("http://dbpedia.org/ontology/birthPlace")));
		birthPlaceStmt.setObjectVar(new Var("bp2", new URIImpl("http://dbpedia.org/resource/Netherlands")));

		// Declare the statements as triples to instantiate to get solutions
		request.addStatementPattern(birthPlaceStmt);
		request.addStatementPattern(artistStmt);
		request.addStatementPattern(fieldStmt);

		// Add the statements as constraints
		request.addConstraint(new StatementPatternConstraint(birthPlaceStmt));
		request.addConstraint(new StatementPatternConstraint(artistStmt));
		request.addConstraint(new StatementPatternConstraint(fieldStmt));

		// The birth and type can be used as data providers
		request.addResourceProvider(new StatementPatternProvider(birthPlaceStmt));
		request.addResourceProvider(new StatementPatternProvider(artistStmt));

		// Create the optimiser
		optimizer = new Optimizer(datalayer, request, null);
		optimizer.addObserver(this);
	}

	/**
	 * @param args
	 * @throws IOException
	 * @throws FileNotFoundException
	 */
	public static void main(String[] args) throws FileNotFoundException, IOException {
		TestQuery test = new TestQuery();
		test.run();
	}

	/**
	 * 
	 */
	private void run() {
		optimizer.run();
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see java.util.Observer#update(java.util.Observable, java.lang.Object)
	 */
	public void update(Observable source, Object arg) {
		// Check source
		if (!(source instanceof Optimizer))
			return;

		// Get the best solution
		@SuppressWarnings("unchecked")
		Collection<Solution> solutions = (Collection<Solution>) arg;
		boolean stop = false;
		for (Solution s : solutions) {
			logger.info("Solution:" + s.toString());
			if (s.isOptimal()) {
				logger.info("Found optimal solution:");
				for (Triple triple : request.getTripleSet(s))
					logger.info(triple.toString());
				stop = true;
			}
		}

		// If we should stop, do it
		if (stop) {
			optimizer.terminate();
			datalayer.shutdown();
		}
	}
}
