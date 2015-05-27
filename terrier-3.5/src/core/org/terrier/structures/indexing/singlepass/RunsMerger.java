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
 * The Original Code is RunsMerger.java.
 *
 * The Original Code is Copyright (C) 2004-2011 the University of Glasgow.
 * All Rights Reserved.
 *
 * Contributor(s):
 *   Roi Blanco (rblanc{at}@udc.es)
 *   Craig Macdonald (craigm{at}dcs.gla.ac.uk)
 */
package org.terrier.structures.indexing.singlepass;


import java.io.IOException;
import java.io.Serializable;
import java.util.Comparator;
import java.util.PriorityQueue;
import java.util.Queue;

import org.terrier.compression.BitOut;
import org.terrier.compression.BitOutputStream;
import org.terrier.structures.BasicLexiconEntry;
import org.terrier.structures.BitFilePosition;
import org.terrier.structures.FilePosition;
import org.terrier.structures.LexiconEntry;
import org.terrier.structures.LexiconOutputStream;

/**
 * Merges a set of N runs using a priority queue. Each element of the queue is a {@link org.terrier.structures.indexing.singlepass.RunIterator}
 * each one pointing at a different run in disk. Each run is sorted, so we only need to compare the heads of the 
 * element in the queue in each merging step.
 * As the runs are being merged, they are written (to disk) using a {@link org.terrier.compression.BitOut}. 
 * @author Roi Blanco and Craig Macdonald
 * @since 2.0
  */
public class RunsMerger {
	
	/**
	 * Implements a comparator for RunIterators (so it can be used by the queue).
	 * It decides the next reader by the lexicographical order of the terms in the top elements of the readers.
	 * @author Roi Blanco and Craig Macdonald
	 */
	public static class PostingComparator implements Comparator<RunIterator>, Serializable{
		/** generated by eclipse */
		private static final long serialVersionUID = 8674662139960016073L;
		@Override
		public int compare (RunIterator a, RunIterator b)
		{
			int tmp = a.current().getTerm().compareTo(b.current().getTerm());
			return tmp != 0 ? tmp : a.getRunNo() - b.getRunNo(); 
		}
	}
	
	/**
	 * Heap for the postings coming from different runs.
	 * It uses an alphabetical order using the terms
	 */	 
	protected Queue<RunIterator> queue;		
	/** BitOut used to write the merged postings to disk*/
	protected BitOut bos;	
	/** RunReader reference for instantiation*/
	//protected RunReader run;
	/** Last term written to disk (useful for terms appearing in mutiple runs */
	protected String lastTermWritten = "";
	
	protected LexiconEntry termStatistics = null;
	
	/** Frequency in the run of the last term merged */ 
	protected int lastFreq = 0;
	/**Last document written in the stream*/
	protected int lastDocument = 0; 
	/** Last document's frequency*/
	protected int lastDocFreq = 0;
	/** RunReader reference for merging */
	protected RunIterator myRun;
	/** Number of terms written */
	protected int currentTerm = 0;
	/** Number of pointers written */
	protected int numberOfPointers = 0;

	protected BitFilePosition startOffset = new FilePosition(0l,(byte)0);

	
	protected RunIteratorFactory runsSource;
	/**
	 * constructor
	 * @param _runsSource
	 */
	public RunsMerger(RunIteratorFactory _runsSource)
	{
		runsSource = _runsSource;
	}
	
	/**
	 * @return the last frequency written.
	 */
	public int getLastFreq(){
		return lastFreq;
	}
	
	/**
	 * @return the last document frequency written.
	 */
	public int getLastDocFreq(){
		return lastDocFreq;
	}	
	
	/**
	 * @return the number of terms written.
	 */
	public int getNumberOfTerms(){
		return currentTerm;
	}
	
	/**
	 * @return the number of pointers written.
	 */
	public int getNumberOfPointers(){
		return numberOfPointers;
	}
	
	/** Indicates whether the merging is done or not
	 * @return true if there are no more elements to merge
	 */
	public boolean isDone(){
		return queue.isEmpty();
	}
	
	/**
	 * @return the byte offset in the BitOut (used for lexicon writting)
	 */
	public long getByteOffset(){
		return bos.getByteOffset();
		//return bos.getBitOffset() == 0? bos.getByteOffset() - 1: bos.getByteOffset(); 
	}
	
	/**
	 * @return the bit offset in the BitOut (used for lexicon writting)
	 */
	public byte getBitOffset(){
		return bos.getBitOffset();
		//return bos.getBitOffset() == 0 ? (byte)7 : bos.getBitOffset() - (byte)1;
	}
	
	/**
	 * @return the String with the last term written to disk.
	 */
	public String getLastTermWritten() {
		return lastTermWritten;
	}
	
	/**
	 * Setter for the last term written.
	 * @param _lastTermWritten String with the last term written. 
	 */
	public void setLastTermWritten(String _lastTermWritten) {
		this.lastTermWritten = _lastTermWritten;
	}
	
	/**
	 * Begins the merge, initilialising the structures.
	 * Notice that the file names must be in order of run-id	
	 * @param size number of runs in disk.
	 * @param fileName String with the file name of the final inverted file.
	 * @throws IOException if an I/O error occurs.
	 */
	protected void init(int size, String fileName) throws Exception{
		this.init(size, new BitOutputStream(fileName));
	}
	
	protected void init(int size, BitOut invertedFile) throws Exception{
		queue = new PriorityQueue<RunIterator>(size, new PostingComparator());
		bos = invertedFile;
		for(int i = 0; i < size; i++){	
			RunIterator run = runsSource.createRunIterator(i);
			run.next();
			queue.add(run);
		}
	}
	
	/**
	 * Begins the multiway merging phase.
	 * @param size number of runs to be merged.
	 * @param fileName output filename.
	 * @throws Exception if an I/O error occurs. 
	 */
	public void beginMerge(int size, String fileName) throws Exception{		
		init(size, fileName);
		myRun = queue.poll();
		while(myRun.current().getTerm().equals(" ")) myRun = queue.poll();		
		lastDocument = myRun.current().append(bos,-1);
		termStatistics = myRun.current().getLexiconEntry();
		lastFreq = myRun.current().getTF();
		lastDocFreq = myRun.current().getDf();	
		lastTermWritten = myRun.current().getTerm();
		if(myRun.hasNext()){
			myRun.next();			
			queue.add(myRun);			
		}else{
			myRun.close();
		}		
	}
	
	/**
	 * Mergers one term in the runs. If a run is exhausted, it is closed and removed from the queue. 
	 * @param lexStream LexiconOutputStream used to write the lexicon.
	 * @throws Exception if an I/O error occurs.
	 */
	public void mergeOne(LexiconOutputStream<String> lexStream) throws Exception{		
		myRun = queue.poll();
		if(myRun.current().getTerm().equals(lastTermWritten)){
			// append the term --> keep the data in memory
			lastDocument = myRun.current().append(bos, lastDocument);
			myRun.current().addToLexiconEntry(termStatistics);
			lastFreq += myRun.current().getTF();
			lastDocFreq += myRun.current().getDf();
			
		}else{			
			//write this term to the lexicon
			termStatistics.setTermId(currentTerm++);
			((BasicLexiconEntry)termStatistics).setOffset(startOffset);
			lexStream.writeNextEntry(lastTermWritten, termStatistics);
			//record the start offset of the next term
			startOffset.setOffset(this.getByteOffset(), this.getBitOffset());
			//get the information of the next term from the Run
			numberOfPointers += lastDocFreq;
			lastDocument = myRun.current().append(bos,-1);
			termStatistics = myRun.current().getLexiconEntry();
			lastFreq = myRun.current().getTF();
			lastDocFreq = myRun.current().getDf();
			lastTermWritten = myRun.current().getTerm();
			
		}
		if(myRun.hasNext()){
			myRun.next();
			queue.add(myRun);
		}else{
			myRun.close();
		}
	}
	
	/**
	 * Ends the merging phase, writes the last entry and closes the streams.
	 * @param lexStream LexiconOutputStream used to write the lexicon.
	 * @throws IOException if an I/O error occurs.	
	 */	
	public void endMerge(LexiconOutputStream<String> lexStream) throws IOException{
		termStatistics.setTermId(currentTerm++);
		((BasicLexiconEntry)termStatistics).setOffset(startOffset);
		lexStream.writeNextEntry(lastTermWritten, termStatistics);
		
		//lexStream.writeNextEntry(lastTermWritten, new BasicLexiconEntry(currentTerm++, lastDocFreq, lastFreq, startOffset));
		//startOffset.setPosition(this.getByteOffset(), this.getBitOffset());
		numberOfPointers += lastDocFreq;
		bos.close();
		myRun.close();
	}	

	/**
	 * getBos
	 * @return BitOut
	 */
	public BitOut getBos() {
		return bos;
	}

	/**
	 * setBos
	 * @param _bos
	 */
	public void setBos(BitOut _bos) {
		this.bos = _bos;
	}
}
