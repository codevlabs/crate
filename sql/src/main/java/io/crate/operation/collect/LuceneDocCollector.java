/*
 * Licensed to CRATE Technology GmbH ("Crate") under one or more contributor
 * license agreements.  See the NOTICE file distributed with this work for
 * additional information regarding copyright ownership.  Crate licenses
 * this file to you under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.  You may
 * obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.  See the
 * License for the specific language governing permissions and limitations
 * under the License.
 *
 * However, if you have executed another commercial license agreement
 * with Crate these terms will supersede the license and you may use the
 * software solely pursuant to the terms of the relevant commercial agreement.
 */

package io.crate.operation.collect;

import io.crate.Constants;
import io.crate.action.sql.query.CrateSearchContext;
import io.crate.action.sql.query.LuceneSortGenerator;
import io.crate.analyze.OrderBy;
import io.crate.breaker.CrateCircuitBreakerService;
import io.crate.breaker.RamAccountingContext;
import io.crate.lucene.QueryBuilderHelper;
import io.crate.metadata.Functions;
import io.crate.operation.*;
import io.crate.operation.reference.doc.lucene.*;
import io.crate.planner.node.dql.CollectNode;
import io.crate.planner.symbol.Reference;
import io.crate.planner.symbol.Symbol;
import org.apache.lucene.index.*;
import org.apache.lucene.search.*;
import org.elasticsearch.common.Nullable;
import org.elasticsearch.index.fieldvisitor.FieldsVisitor;
import org.elasticsearch.index.mapper.internal.SourceFieldMapper;
import org.elasticsearch.search.internal.ContextIndexSearcher;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.CancellationException;

/**
 * collect documents from ES shard, a lucene index
 */
public class LuceneDocCollector extends Collector implements CrateCollector, RowUpstream {


    public static class CollectorFieldsVisitor extends FieldsVisitor {

        final HashSet<String> requiredFields;
        private boolean required = false;

        public CollectorFieldsVisitor(int size) {
            requiredFields = new HashSet<>(size);
        }

        public boolean addField(String name) {
            required = true;
            return requiredFields.add(name);
        }

        public boolean required() {
            return required;
        }

        @Override
        public Status needsField(FieldInfo fieldInfo) throws IOException {
            if (SourceFieldMapper.NAME.equals(fieldInfo.name)) {
                return Status.YES;
            }
            return requiredFields.contains(fieldInfo.name) ? Status.YES : Status.NO;
        }

        public void required(boolean required) {
            this.required = required;
        }
    }

    private CollectInputSymbolVisitor<LuceneCollectorExpression<?>> inputSymbolVisitor;

    private final RowDownstreamHandle downstream;
    private final CollectorFieldsVisitor fieldsVisitor;
    private final InputRow inputRow;
    private final List<LuceneCollectorExpression<?>> collectorExpressions;
    private final JobQueryShardContext shardContext;
    private final CrateSearchContext searchContext;
    private final List<OrderByCollectorExpression> orderByCollectorExpressions = new ArrayList<>();
    private final Integer limit;
    private final OrderBy orderBy;
    private final RamAccountingContext ramAccountingContext;

    private volatile boolean killed = false;
    private boolean visitorEnabled = false;
    private AtomicReader currentReader;
    private boolean producedRows = false;
    private boolean failed = false;
    private int rowCount = 0;
    private int pageSize;

    public LuceneDocCollector(List<Input<?>> inputs,
                              List<LuceneCollectorExpression<?>> collectorExpressions,
                              CollectNode collectNode,
                              Functions functions,
                              RowDownstream downStreamProjector,
                              JobQueryShardContext shardContext,
                              RamAccountingContext ramAccountingContext) throws Exception {
        this.shardContext = shardContext;
        this.ramAccountingContext = ramAccountingContext;
        this.limit = collectNode.limit();
        this.orderBy = collectNode.orderBy();
        this.downstream = downStreamProjector.registerUpstream(this);
        this.inputRow = new InputRow(inputs);
        this.collectorExpressions = collectorExpressions;
        for (LuceneCollectorExpression expr : collectorExpressions) {
            if ( expr instanceof OrderByCollectorExpression) {
                orderByCollectorExpressions.add((OrderByCollectorExpression)expr);
            }
        }
        this.fieldsVisitor = new CollectorFieldsVisitor(collectorExpressions.size());
        this.searchContext = shardContext.searchContext();
        inputSymbolVisitor = new CollectInputSymbolVisitor<>(functions, new LuceneDocLevelReferenceResolver(null));
        this.pageSize = Constants.PAGE_SIZE;
    }

    @Override
    public void setScorer(Scorer scorer) throws IOException {
        for (LuceneCollectorExpression expr : collectorExpressions) {
            expr.setScorer(scorer);
        }
    }

    @Override
    public void collect(int doc) throws IOException {
        if (killed) {
            throw new CancellationException();
        }
        if (ramAccountingContext != null && ramAccountingContext.trippedBreaker()) {
            // stop collecting because breaker limit was reached
            throw new UnexpectedCollectionTerminatedException(
                    CrateCircuitBreakerService.breakingExceptionMessage(ramAccountingContext.contextId(),
                            ramAccountingContext.limit()));
        }

        rowCount++;
        producedRows = true;
        if (visitorEnabled) {
            fieldsVisitor.reset();
            currentReader.document(doc, fieldsVisitor);
        }
        for (LuceneCollectorExpression e : collectorExpressions) {
            e.setNextDocId(doc);
        }
        boolean wantMore = downstream.setNextRow(inputRow);
        if (!wantMore || (limit != null && rowCount == limit)) {
            // no more rows required, we can stop here
            throw new CollectionFinishedEarlyException();
        }
    }

    @Override
    public void setNextReader(AtomicReaderContext context) throws IOException {
        this.currentReader = context.reader();
        for (LuceneCollectorExpression expr : collectorExpressions) {
            expr.setNextReader(context);
        }
    }

    public void setNextOrderByValues(ScoreDoc scoreDoc) {
        for (OrderByCollectorExpression expr : orderByCollectorExpressions) {
            expr.setNextFieldDoc((FieldDoc) scoreDoc);
        }
    }

    @Override
    public boolean acceptsDocsOutOfOrder() {
        return true;
    }

    @Override
    public void doCollect() {
        // start collect
        CollectorContext collectorContext = new CollectorContext()
                .searchContext(searchContext)
                .visitor(fieldsVisitor)
                .jobSearchContextId(shardContext.jobSearchContextId());
        for (LuceneCollectorExpression<?> collectorExpression : collectorExpressions) {
            collectorExpression.startCollect(collectorContext);
        }
        visitorEnabled = fieldsVisitor.required();
        shardContext.acquireContext();
        searchContext.searcher().inStage(ContextIndexSearcher.Stage.MAIN_QUERY);
        Query query = searchContext.query();

        try {
            assert query != null : "query must not be null";

            if(orderBy != null) {
                searchWithOrderBy(query);
            } else {
                searchContext.searcher().search(query, this);
            }
            downstream.finish();
        } catch (CollectionFinishedEarlyException e) {
            downstream.finish();
        } catch (Throwable e) {
            failed = true;
            downstream.fail(e);
        } finally {
            searchContext().searcher().finishStage(ContextIndexSearcher.Stage.MAIN_QUERY);
            shardContext.releaseContext();
            shardContext.close();
        }
    }

    @Override
    public void kill() {
        killed = true;
    }

    private void searchWithOrderBy(Query query) throws IOException {
        Integer batchSize = limit == null ? pageSize : Math.min(pageSize, limit);
        Sort sort = LuceneSortGenerator.generateLuceneSort(searchContext, orderBy, inputSymbolVisitor);
        TopFieldDocs topFieldDocs = searchContext.searcher().search(query, batchSize, sort);
        int collected = topFieldDocs.scoreDocs.length;

        Collection<ScoreCollectorExpression> scoreExpressions = getScoreExpressions();
        ScoreDoc lastCollected = collectTopFields(topFieldDocs, scoreExpressions);
        while ((limit == null || collected < limit) && topFieldDocs.scoreDocs.length >= batchSize && lastCollected != null) {
            if (killed) {
                throw new CancellationException();
            }

            batchSize = limit == null ? pageSize : Math.min(pageSize, limit - collected);
            Query alreadyCollectedQuery = alreadyCollectedQuery((FieldDoc)lastCollected);
            if (alreadyCollectedQuery != null) {
                BooleanQuery searchAfterQuery = new BooleanQuery();
                searchAfterQuery.add(query, BooleanClause.Occur.MUST);
                searchAfterQuery.add(alreadyCollectedQuery, BooleanClause.Occur.MUST_NOT);
                topFieldDocs = (TopFieldDocs)searchContext.searcher().searchAfter(lastCollected, searchAfterQuery, batchSize, sort);
            } else {
                topFieldDocs = (TopFieldDocs)searchContext.searcher().searchAfter(lastCollected, query, batchSize, sort);
            }
            collected += topFieldDocs.scoreDocs.length;
            lastCollected = collectTopFields(topFieldDocs, scoreExpressions);
        }
    }

    private Collection<ScoreCollectorExpression> getScoreExpressions() {
        List<ScoreCollectorExpression> scoreCollectorExpressions = new ArrayList<>();
        for (LuceneCollectorExpression<?> expression : collectorExpressions) {
            if (expression instanceof ScoreCollectorExpression) {
                scoreCollectorExpressions.add((ScoreCollectorExpression) expression);
            }
        }
        return scoreCollectorExpressions;
    }

    public CrateSearchContext searchContext() {
        return searchContext;
    }

    public boolean producedRows() {
        return producedRows;
    }

    public boolean failed() {
        return failed;
    }

    public void pageSize(int pageSize) {
        this.pageSize = pageSize;
    }

    private ScoreDoc collectTopFields(TopFieldDocs topFieldDocs, Collection<ScoreCollectorExpression> scoreExpressions) throws IOException{
        IndexReaderContext indexReaderContext = searchContext.searcher().getTopReaderContext();
        ScoreDoc lastDoc = null;
        if(!indexReaderContext.leaves().isEmpty()) {
            for (ScoreDoc scoreDoc : topFieldDocs.scoreDocs) {
                int readerIndex = ReaderUtil.subIndex(scoreDoc.doc, searchContext.searcher().getIndexReader().leaves());
                AtomicReaderContext subReaderContext = searchContext.searcher().getIndexReader().leaves().get(readerIndex);
                int subDoc = scoreDoc.doc - subReaderContext.docBase;
                setNextReader(subReaderContext);
                setNextOrderByValues(scoreDoc);
                for (LuceneCollectorExpression<?> scoreExpression : scoreExpressions) {
                    ((ScoreCollectorExpression) scoreExpression).score(scoreDoc.score);
                }
                collect(subDoc);
                lastDoc = scoreDoc;
            }
        }
        return lastDoc;
    }

    private @Nullable Query alreadyCollectedQuery(FieldDoc lastCollected) {
        BooleanQuery query = new BooleanQuery();
        for (int i = 0; i < orderBy.orderBySymbols().size(); i++) {
            Symbol order = orderBy.orderBySymbols().get(i);
            Object value = lastCollected.fields[i];
            // only filter for null values if nulls last
            if (order instanceof Reference && (value != null || !orderBy.nullsFirst()[i])) {
                QueryBuilderHelper helper = QueryBuilderHelper.forType(order.valueType());
                String columnName = ((Reference) order).info().ident().columnIdent().fqn();
                if (orderBy.reverseFlags()[i]) {
                    query.add(helper.rangeQuery(columnName, value, null, false, false), BooleanClause.Occur.MUST);
                } else {
                    query.add(helper.rangeQuery(columnName, null, value, false, false), BooleanClause.Occur.MUST);
                }
            }
        }
        if (query.clauses().size() > 0) {
            return query;
        } else {
            return null;
        }
    }
}
