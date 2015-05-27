
/*
 * Terrier - Terabyte Retriever 
 * Webpage: http://terrier.org 
 * Contact: terrier{a.}dcs.gla.ac.uk
 * University of Glasgow - School of Computing Science
 * http://www.gla.ac.uk/
 * 
 * The contents of this file are subject to the Mozilla Public License
 * Version 1.1 (the "License"); you may not use this file except in
 * compliance with the License. You may obtain a copy of the License at
 * http://www.mozilla.org/MPL/
 *
 * Software distributed under the License is distributed on an "AS IS"
 * basis, WITHOUT WARRANTY OF ANY KIND, either express or implied. See
 * the License for the specific language governing rights and limitations
 * under the License.
 *
 * The Original Code is TRECResultsMatching.java.
 *
 * The Original Code is Copyright (C) 2004-2011 the University of Glasgow.
 * All Rights Reserved.
 *
 * Contributor(s):
 *   Rodrygo Santos <rodrygo{a.}dcs.gla.ac.uk>  (original author)
 *   Craig Macdonald <craigm{a.}dcs.gla.ac.uk> 
 */
package org.terrier.matching;

import java.io.BufferedReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import org.apache.log4j.Logger;
import org.terrier.matching.dsms.DocumentScoreModifier;
import org.terrier.structures.CollectionStatistics;
import org.terrier.structures.Index;
import org.terrier.utility.ApplicationSetup;
import org.terrier.utility.Files;
import org.terrier.utility.HeapSort;

/**
 * A matching implementation that retrieves results from a TREC result file
 * rather than the current index. Such a result file must be compatible with
 * trec_eval, i.e., it should have the following format:
 * 
 * <pre>queryID Q0 docno score rank label</pre>
 * 
 * <p><b>Properties:</b>
 * <ul>
 * <li><tt>matching.trecresults.file</tt> - the path to the TREC results file.</li>
 * <li><tt>matching.trecresults.format</tt> - the input format to parse document identifiers. Defaults to DOCNO. 
 * If DOCID is specified, then the docnos are assumed to represent Terrier's docids, as generated by
 * {@link org.terrier.applications.TRECQuerying.TRECDocidOutputFormat}.</li>
 * <li><tt>matching.trecresults.scores</tt> - whether scores should be parsed. Defaults to true.</li>
 * <li><tt>matching.trecresults.length</tt> - the maximum number of documents per query. Defaults to 1000.
 * Note that setting this property to 0 may slow down the retrieval process for large collections, as a 
 * result set of the size of the collection will be allocated in memory.</li>
 * </ul>
 * 
 * @author Craig Macdonald, Rodrygo Santos
 */
public class TRECResultsMatching implements Matching {

	protected static final Pattern SPLIT_SPACE_PLUS = Pattern.compile("\\s+");
	
	/** The underlying index. */
	protected Index index;
	/** The underlying collections statistics. */
	protected CollectionStatistics collStats;	
	/** The default namespace for document score modifiers. */
	protected static final String DSMNS = "org.terrier.matching.dsms.";
	/** The list of document score modifiers to be applied. */
	protected List<DocumentScoreModifier> dsms;
	/** The TREC results filename. */
	protected String filename;
	/** The TREC results file reader. */
	protected BufferedReader reader;
	/** The input format to use when parsing document identifiers. */
	protected final InputFormat format;
	/** Whether document scores should be parsed from the results file. */
	protected final boolean parseScores;
	/** The maximum number of results to read per query. */
	protected final int maxResults;
	/** The current read document identifier. */
	protected int docid;
	/** The current read score. */
	protected double score;
	/** Whether the current query was found in the results file. */
	protected boolean found;
	/** Whether the current file has already been reset. */
	protected boolean reset;
	/** This object's logger. */
	protected Logger logger;
	private String qid;
	/** input format */
	public enum InputFormat {
		/** docno, docid */
		DOCNO, DOCID;
	}
	/** 
	 * Contructs an instance of the TRECResultsMatching given an index.
	 * @param _index
	 * @throws IOException
	 */
	public TRECResultsMatching(Index _index) throws IOException {
		this(_index, ApplicationSetup.getProperty("matching.trecresults.file", ""), ApplicationSetup.getProperty("matching.dsms", ""));
	}
	/** 
	 * Contructs an instance of the TRECResultsMatching.
	 * @param _index
	 * @param _filename
	 * @throws IOException
	 */
	public TRECResultsMatching(Index _index, String _filename) throws IOException {
		this(_index, _filename, ApplicationSetup.getProperty("matching.dsms", ""));
	}
	/** 
	 * Contructs an instance of the TRECResultsMatching.
	 * @param _index
	 * @param _filename
	 * @param defDSMs
	 * @throws IOException
	 */
	public TRECResultsMatching(Index _index, String _filename, String defDSMs) throws IOException {
		this.logger = Logger.getLogger(TRECResultsMatching.class);
		
		this.index = _index;
		this.collStats = _index.getCollectionStatistics();
		this.initDSMs(defDSMs);
		
		this.filename = _filename;
		this.format = InputFormat.valueOf(ApplicationSetup.getProperty("matching.trecresults.format", "docno").toUpperCase());
		this.parseScores = Boolean.parseBoolean(ApplicationSetup.getProperty("matching.trecresults.scores", "true"));
		this.maxResults = Integer.parseInt(ApplicationSetup.getProperty("matching.trecresults.length", "1000"));
		
		reopen();
	}
	
	protected void reopen() throws IOException {
		if (reader != null) {
			try {
				reader.close();
			} catch (IOException e) {}
		}
		
		reader = Files.openFileReader(filename);
		logger.info(this.getClass().getSimpleName() + " opened " + filename);
	}
	
	protected void initDSMs(String defDSMs) {
		dsms = new ArrayList<DocumentScoreModifier>();
		try {
			for(String modifierName : defDSMs.split("\\s*,\\s*")) {
				if (modifierName.length() == 0)
                    continue;
				if (modifierName.indexOf('.') == -1)
					modifierName = DSMNS + modifierName;
				dsms.add((DocumentScoreModifier) Class.forName(modifierName).newInstance());
			}
		} catch(Exception e) {
			logger.error("Exception while initialising default modifiers. Please check the name of the modifiers in the configuration file.", e);
		}
	}

	@Override
	public String getInfo() {
		return this.getClass().getSimpleName() + "(" + this.filename + ")";
	}

	protected int getDocid(String docno) throws IOException {
		if (format.equals(InputFormat.DOCNO)) {
			return index.getMetaIndex().getDocument("docno", docno);
		}
		else {
			return Integer.parseInt(docno);
		}
	}
	
	protected boolean read(String _qid) throws IOException {

		while (true) {
			reader.mark(1024);
			String line = reader.readLine();
			// have we reached EOF?
			if (line == null) {
				this.qid = null;				
				// could the desired qid be somewhere above?
				if (!found && !reset) {
					reopen();
					reset = true;
					continue;
				}
				return false;
			}
			// got a valid line?
			else {
				String[] parts = SPLIT_SPACE_PLUS.split(line.trim());
				this.qid = parts[0];
				this.docid = getDocid(parts[2]);
				if (parseScores) {
					this.score = Double.parseDouble(parts[4]);
				}
				
				// is it the desired qid?
				if (parts[0].equals(_qid)) {
					found = true;
					return true;
				}
				// have we entered another query?
				else if (found) {
					reader.reset();
					return false;
				}
			}
		}
	}
	
	@Override
	public ResultSet match(String _qid, MatchingQueryTerms mqt) throws IOException {
		int max = collStats.getNumberOfDocuments();
		if (maxResults > 0) {
			max = maxResults;
		}
		ResultSet rs = new CollectionResultSet(max);
		rs.initialise();

		int[] docids = rs.getDocids();
		double[] scores = rs.getScores();
		
		found = false;
		reset = false;
		
		int matched = 0;
		while (read(_qid) && matched < max) {
			docids[matched] = docid;
			scores[matched] = score;
			matched++;
		}
		
		if (_qid.equals(this.qid)) {
			logger.warn("Found more than " + max + " results for query " + _qid);
		}		
		
		rs.setExactResultSize(matched);
		rs.setResultSize(matched);
		
		// crop to the actual size
		rs = rs.getResultSet(0, matched);
		docids = rs.getDocids();
		scores = rs.getScores();
		
		// the number of document score modifiers
		int numDSMs = dsms.size();
		
		/*application dependent modification of scores
		of documents for a query, based on a static set by the client code
		sorting the result set after applying each DSM*/
		logger.info("Applying " + numDSMs + " DSMs to query " + _qid);
		
		for (int t = 0; t < numDSMs; t++) {
			if (dsms.get(t).modifyScores(index, mqt, rs)) {
				HeapSort.descendingHeapSort(scores, docids, rs.getOccurrences(), rs.getResultSize());
			}				
		}
		
		logger.debug(this.getClass().getSimpleName() + " ranked " + (matched) + " documents in response to query " + _qid);
	
		return rs;
	}

	@Override
	public void setCollectionStatistics(CollectionStatistics _collStats) {
		this.collStats = _collStats;
	}
	/**
	 * Returns collection statistics.
	 * @return collection statistics
	 */
	public CollectionStatistics getCollectionStatistics() {
		return collStats;
	}
	
	@Override
	protected void finalize() throws Throwable {
		reader.close();
	}

}
