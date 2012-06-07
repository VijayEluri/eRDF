/**
 * 
 */
package nl.erdf.datalayer.hbase;

import java.io.IOException;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

import nl.erdf.datalayer.DataLayer;
import nl.erdf.model.Triple;
import nl.erdf.util.Randomizer;
import nl.vu.datalayer.hbase.HBaseClientSolution;
import nl.vu.datalayer.hbase.HBaseFactory;
import nl.vu.datalayer.hbase.connection.HBaseConnection;
import nl.vu.datalayer.hbase.schema.HBPrefixMatchSchema;

import org.apache.jcs.JCS;
import org.apache.jcs.access.exception.CacheException;
import org.openrdf.model.Statement;
import org.openrdf.model.Value;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @author Christophe Guéret <christophe.gueret@gmail.com>
 * 
 */
public class SeverHBaseDataLayer implements DataLayer {
	// Logger
	protected static final Logger logger = LoggerFactory.getLogger(SeverHBaseDataLayer.class);

	private HBaseConnection con;

	private HBaseClientSolution sol;

	// Cache for the number of resources
	private final Map<Triple, Long> countersCache = new HashMap<Triple, Long>();

	// General cache
	private JCS resultCache;

	/**
	 * @param connectionType
	 * @param useCache
	 * 
	 */
	public SeverHBaseDataLayer(byte connectionType, boolean useCache) {
		try {
			con = HBaseConnection.create(connectionType);
			sol = HBaseFactory.getHBaseSolution(HBPrefixMatchSchema.SCHEMA_NAME, con, null, useCache);
		} catch (IOException e) {
			e.printStackTrace();
		}

		try {
			resultCache = JCS.getInstance("validate");
		} catch (CacheException e) {
			e.printStackTrace();
		}

	}

	/**
	 * @param pattern
	 * @return
	 * @throws IOException
	 */
	private int retrieveInternal(Triple pattern) {
		if (pattern.getSubject() == null)
			return 0;
		else if (pattern.getPredicate() == null)
			return 1;
		else if (pattern.getObject() == null)
			return 2;
		else if (pattern.getContext() == null)
			return 3;
		else
			return -1;
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see
	 * nl.erdf.datalayer.DataLayer#getNumberOfResources(nl.erdf.model.Triple)
	 */
	public synchronized long getNumberOfResources(Triple pattern) {
		long results = 0;

		if (countersCache.containsKey(pattern)) {
			results = countersCache.get(pattern);
		} else {
			// logger.info("[CNT] " + pattern);
			try {
				Value[] quad = { pattern.getSubject(), pattern.getPredicate(), pattern.getObject(),
						pattern.getContext() };
				results = sol.util.getNumberResults(quad);
			} catch (IOException e) {
				e.printStackTrace();
			}
			countersCache.put(pattern, new Long(results));
		}
		return results;

	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see nl.erdf.datalayer.DataLayer#getResource(nl.erdf.model.Triple)
	 */
	public Value getResource(Triple pattern) {
		try {
			// logger.info("[GET] " + pattern);

			Value[] quad = { pattern.getSubject(), pattern.getPredicate(), pattern.getObject(), null };
			Value[] result = sol.util.getSingleResult(quad, Randomizer.instance());
			Value res = (result != null ? result[retrieveInternal(pattern)] : null);

			return res;
		} catch (IOException e) {
			e.printStackTrace();
			return null;
		}
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see nl.erdf.datalayer.DataLayer#isValid(nl.erdf.model.Triple)
	 */
	public boolean isValid(Triple pattern) {
		// null patterns can not be valid
		if (pattern == null)
			return false;

		Boolean result = (Boolean) resultCache.get(pattern);
		if (result == null) {
			// logger.info("[VLD] " + pattern);

			if (pattern.getNumberNulls() != 0) {
				// Check a partial pattern
				result = new Boolean(getResource(pattern) != null);
			} else {
				// Check a fully instantiated triple
				try {
					Value[] quad = { pattern.getSubject(), pattern.getPredicate(), null, null };
					Collection<Value[]> results = sol.util.getAllResults(quad);
					if (results == null) {
						result = new Boolean(false);
					} else {
						for (Value[] r : results)
							if (r[2] != null && r[2].equals(pattern.getObject()))
								result = new Boolean(true);
					}
				} catch (IOException e) {
					e.printStackTrace();
				}

				if (result == null)
					result = new Boolean(false);
			}
		}

		try {
			resultCache.put(pattern, result);
		} catch (CacheException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}

		return result.booleanValue();

	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see nl.erdf.datalayer.DataLayer#add(org.openrdf.model.Statement)
	 */
	public void add(Statement statement) {
		// Not implemented
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see nl.erdf.datalayer.DataLayer#clear()
	 */
	public void clear() {
		// Not implemented
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see nl.erdf.datalayer.DataLayer#shutdown()
	 */
	public void shutdown() {
		try {
			con.close();
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see nl.erdf.datalayer.DataLayer#waitForLatencyBuffer()
	 */
	public void waitForLatencyBuffer() {
		// Not implemented
	}

}