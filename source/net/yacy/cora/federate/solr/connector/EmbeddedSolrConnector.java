/**
 *  EmbeddedSolrConnector
 *  Copyright 2012 by Michael Peter Christen
 *  First released 21.06.2012 at http://yacy.net
 *
 *  This library is free software; you can redistribute it and/or
 *  modify it under the terms of the GNU Lesser General Public
 *  License as published by the Free Software Foundation; either
 *  version 2.1 of the License, or (at your option) any later version.
 *
 *  This library is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 *  Lesser General Public License for more details.
 *
 *  You should have received a copy of the GNU Lesser General Public License
 *  along with this program in the file lgpl21.txt
 *  If not, see <http://www.gnu.org/licenses/>.
 */


package net.yacy.cora.federate.solr.connector;

import java.io.IOException;
import java.util.HashSet;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

import net.yacy.cora.federate.solr.instance.EmbeddedInstance;
import net.yacy.cora.federate.solr.instance.SolrInstance;
import net.yacy.cora.util.ConcurrentLog;
import net.yacy.search.schema.CollectionSchema;

import org.apache.lucene.document.Document;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.search.Query;
import org.apache.solr.client.solrj.SolrQuery;
import org.apache.solr.client.solrj.SolrServerException;
import org.apache.solr.client.solrj.response.QueryResponse;
import org.apache.solr.common.SolrException;
import org.apache.solr.common.params.ModifiableSolrParams;
import org.apache.solr.common.params.SolrParams;
import org.apache.solr.common.util.NamedList;
import org.apache.solr.common.util.SimpleOrderedMap;
import org.apache.solr.core.SolrConfig;
import org.apache.solr.core.SolrCore;
import org.apache.solr.handler.component.SearchHandler;
import org.apache.solr.request.SolrQueryRequest;
import org.apache.solr.request.SolrQueryRequestBase;
import org.apache.solr.request.UnInvertedField;
import org.apache.solr.response.ResultContext;
import org.apache.solr.response.SolrQueryResponse;
import org.apache.solr.search.DocIterator;
import org.apache.solr.search.DocList;
import org.apache.solr.search.DocSet;
import org.apache.solr.search.DocSlice;
import org.apache.solr.search.QueryResultKey;
import org.apache.solr.search.SolrCache;
import org.apache.solr.search.SolrIndexSearcher;
import org.apache.solr.util.RefCounted;

public class EmbeddedSolrConnector extends SolrServerConnector implements SolrConnector {

    private static Set<String> SOLR_ID_FIELDS = new HashSet<String>();
    static {
        SOLR_ID_FIELDS.add(CollectionSchema.id.getSolrFieldName());
    }
    
    public static final String SELECT = "/select";
    public static final String CONTEXT = "/solr";
    
    private final SearchHandler requestHandler;
    private final EmbeddedInstance instance;
    private SolrCore core;

    public EmbeddedSolrConnector(EmbeddedInstance instance) {
        super();
        this.instance = instance;
        this.core = this.instance.getDefaultCore();
        this.requestHandler = new SearchHandler();
        this.requestHandler.init(new NamedList<Object>());
        this.requestHandler.inform(this.core);
        super.init(this.instance.getDefaultServer());
    }
    
    public EmbeddedSolrConnector(EmbeddedInstance instance, String coreName) {
        super();
        this.instance = instance;
        this.core = this.instance.getCore(coreName);
        this.requestHandler = new SearchHandler();
        this.requestHandler.init(new NamedList<Object>());
        this.requestHandler.inform(this.core);
        super.init(this.instance.getServer(coreName));
    }

    public void clearCaches() {
        SolrConfig solrConfig = this.core.getSolrConfig();
        @SuppressWarnings("unchecked")
        SolrCache<String, UnInvertedField> fieldValueCache = solrConfig.fieldValueCacheConfig == null ? null : solrConfig.fieldValueCacheConfig.newInstance();
        if (fieldValueCache != null) fieldValueCache.clear();
        @SuppressWarnings("unchecked")
        SolrCache<Query, DocSet> filterCache= solrConfig.filterCacheConfig == null ? null : solrConfig.filterCacheConfig.newInstance();
        if (filterCache != null) filterCache.clear();
        @SuppressWarnings("unchecked")
        SolrCache<QueryResultKey, DocList> queryResultCache = solrConfig.queryResultCacheConfig == null ? null : solrConfig.queryResultCacheConfig.newInstance();
        if (queryResultCache != null) queryResultCache.clear();
        @SuppressWarnings("unchecked")
        SolrCache<Integer, Document> documentCache = solrConfig.documentCacheConfig == null ? null : solrConfig.documentCacheConfig.newInstance();
        if (documentCache != null) documentCache.clear();
        this.core.getInfoRegistry().clear(); // don't know what this is for - but this is getting huge!
    }
    
    public SolrInstance getInstance() {
        return this.instance;
    }
    
    public SolrCore getCore() {
        return this.core;
    }

    public SolrConfig getConfig() {
        return this.core.getSolrConfig();
    }

    @Override
    public synchronized void close() {
        try {this.commit(false);} catch (final Throwable e) {ConcurrentLog.logException(e);}
        try {super.close();} catch (final Throwable e) {ConcurrentLog.logException(e);}
        try {this.core.close();} catch (final Throwable e) {ConcurrentLog.logException(e);}
    }

    @Override
    public long getSize() {
        RefCounted<SolrIndexSearcher> refCountedIndexSearcher = this.core.getSearcher();
        SolrIndexSearcher searcher = refCountedIndexSearcher.get();
        DirectoryReader reader = searcher.getIndexReader();
        long numDocs = reader.numDocs();
        refCountedIndexSearcher.decref();
        return numDocs;
    }

    /**
     * get a new query request. MUST be closed after usage using close()
     * @param params
     * @return
     */
    public SolrQueryRequest request(final SolrParams params) {
        SolrQueryRequest req = new SolrQueryRequestBase(this.core, params){};
        req.getContext().put("path", SELECT);
        req.getContext().put("webapp", CONTEXT);
        return req;
    }
    
    public SolrQueryResponse query(SolrQueryRequest req) throws SolrException {
        final long startTime = System.currentTimeMillis();

        // during the solr query we set the thread name to the query string to get more debugging info in thread dumps
        String q = req.getParams().get("q");
        String threadname = Thread.currentThread().getName();
        if (q != null) Thread.currentThread().setName("solr query: q = " + q);
        
        SolrQueryResponse rsp = new SolrQueryResponse();
        NamedList<Object> responseHeader = new SimpleOrderedMap<Object>();
        responseHeader.add("params", req.getOriginalParams().toNamedList());
        rsp.add("responseHeader", responseHeader);
        //SolrRequestInfo.setRequestInfo(new SolrRequestInfo(req, rsp));

        // send request to solr and create a result
        this.requestHandler.handleRequest(req, rsp);

        // get statistics and add a header with that
        Exception exception = rsp.getException();
        int status = exception == null ? 0 : exception instanceof SolrException ? ((SolrException) exception).code() : 500;
        responseHeader.add("status", status);
        responseHeader.add("QTime",(int) (System.currentTimeMillis() - startTime));

        if (q != null) Thread.currentThread().setName(threadname);
        // return result
        return rsp;
    }

    /**
     * the usage of getResponseByParams is disencouraged for the embedded Solr connector. Please use request(SolrParams) instead.
     * Reason: Solr makes a very complex folding/unfolding including data compression for SolrQueryResponses.
     */
    @Override
    public QueryResponse getResponseByParams(ModifiableSolrParams params) throws IOException {
        if (this.server == null) throw new IOException("server disconnected");
        // during the solr query we set the thread name to the query string to get more debugging info in thread dumps
        String q = params.get("q");
        String threadname = Thread.currentThread().getName();
        if (q != null) Thread.currentThread().setName("solr query: q = " + q);
        QueryResponse rsp;
        try {
            rsp = this.server.query(params);
            if (q != null) Thread.currentThread().setName(threadname);
            if (rsp != null) log.fine(rsp.getResults().getNumFound() + " results for q=" + q);
            return rsp;
        } catch (final SolrServerException e) {
            throw new IOException(e);
        } catch (final Throwable e) {
            throw new IOException("Error executing query", e);
        }
    }

    private class DocListSearcher {
        public SolrQueryRequest request;
        public DocList response;

        public DocListSearcher(final String querystring, final int offset, final int count, final String ... fields) {
            // construct query
            final SolrQuery params = new SolrQuery();
            params.setQuery(querystring);
            params.setRows(count);
            params.setStart(offset);
            params.setFacet(false);
            params.clearSorts();
            if (fields.length > 0) params.setFields(fields);
            params.setIncludeScore(false);
            
            // query the server
            this.request = request(params);
            SolrQueryResponse rsp = query(request);
            @SuppressWarnings("rawtypes")
            NamedList nl = rsp.getValues();
            ResultContext resultContext = (ResultContext) nl.get("response");
            if (resultContext == null) log.warn("DocListSearcher: no response for query '" + querystring + "'");
            this.response = resultContext == null ? new DocSlice(0, 0, new int[0], new float[0], 0, 0.0f) : resultContext.docs;
        }
        public void close() {
            if (this.request != null) this.request.close();
            this.request = null;
            this.response = null;
        }
    }
    
    @Override
    public long getCountByQuery(String querystring) {
    	int numFound = 0;
    	DocListSearcher docListSearcher = null;
        try {
        	docListSearcher = new DocListSearcher(querystring, 0, 0, CollectionSchema.id.getSolrFieldName());
        	numFound = docListSearcher.response.matches();
        } finally { 
        	if (docListSearcher != null) docListSearcher.close();
        }
        return numFound;
    }

    @Override
    public boolean existsById(String id) {
        return getCountByQuery("{!raw f=" + CollectionSchema.id.getSolrFieldName() + "}" + id) > 0;
    }
    
    @Override
    public Set<String> existsByIds(Set<String> ids) {
        if (ids == null || ids.size() == 0) return new HashSet<String>();
        if (ids.size() == 1) return existsById(ids.iterator().next()) ? ids : new HashSet<String>();
        Set<String> idsr = new TreeSet<String>();
        final SolrQuery params = new SolrQuery();
        params.setRows(0);
        params.setStart(0);
        params.setFacet(false);
        params.clearSorts();
        params.setFields(CollectionSchema.id.getSolrFieldName());
        params.setIncludeScore(false);
        SolrQueryRequest req = new SolrQueryRequestBase(this.core, params){};
        req.getContext().put("path", SELECT);
        req.getContext().put("webapp", CONTEXT);
        for (String id: ids) {
            params.setQuery("{!raw f=" + CollectionSchema.id.getSolrFieldName() + "}" + id);
            SolrQueryResponse rsp = new SolrQueryResponse();
            this.requestHandler.handleRequest(req, rsp);
            DocList response = ((ResultContext) rsp.getValues().get("response")).docs;
            if (response.matches() > 0) idsr.add(id);
        }
        req.close();
        return idsr;
    }
    
    @Override
    public String getFieldById(final String id, final String field) throws IOException {
    	String ret = null;
    	DocListSearcher docListSearcher = null;
    	try {
            docListSearcher = new DocListSearcher("{!raw f=" + CollectionSchema.id.getSolrFieldName() + "}" + id, 0, 1, CollectionSchema.id.getSolrFieldName());
	        int numFound = docListSearcher.response.matches();
	        if (numFound > 0) {
		        Set<String> solrFields = new HashSet<String>();
		        solrFields.add(field);
	            Document doc = docListSearcher.request.getSearcher().doc(docListSearcher.response.iterator().nextDoc(), solrFields);
	            ret = doc.get(field);
	        }
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            if (docListSearcher != null) docListSearcher.close();
        }
        return ret;
    }
    
    @Override
    public BlockingQueue<String> concurrentIDsByQuery(final String querystring, final int offset, final int maxcount, final long maxtime) {
        final BlockingQueue<String> queue = new LinkedBlockingQueue<String>();
        final long endtime = maxtime == Long.MAX_VALUE ? Long.MAX_VALUE : System.currentTimeMillis() + maxtime; // we know infinity!
        final Thread t = new Thread() {
            @Override
            public void run() {
                int o = offset, responseCount = 0;
                DocListSearcher docListSearcher = null;
                while (System.currentTimeMillis() < endtime) {
                    try {
                    	responseCount = 0;
                        docListSearcher = new DocListSearcher(querystring, o, pagesize, CollectionSchema.id.getSolrFieldName());
                        responseCount = docListSearcher.response.size();
                        SolrIndexSearcher searcher = docListSearcher.request.getSearcher();
                        DocIterator iterator = docListSearcher.response.iterator();
                        for (int i = 0; i < responseCount; i++) {
                            Document doc = searcher.doc(iterator.nextDoc(), SOLR_ID_FIELDS);
                            try {queue.put(doc.get(CollectionSchema.id.getSolrFieldName()));} catch (final InterruptedException e) {break;}
                        }
                    } catch (final SolrException e) {
                        break;
                    } catch (IOException e) {
                    } finally {
                        if (docListSearcher != null) docListSearcher.close();
                    }
                    if (responseCount < pagesize) break;
                    o += pagesize;
                }
                try {queue.put(AbstractSolrConnector.POISON_ID);} catch (final InterruptedException e1) {}
            }
        };
        t.start();
        return queue;
    }
}
