/*
 * Copyright 2002-2011 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package grails.plugins.elasticsearch

import co.elastic.clients.elasticsearch.ElasticsearchClient
import co.elastic.clients.elasticsearch._types.FieldValue
import co.elastic.clients.elasticsearch._types.SortOptions
import co.elastic.clients.elasticsearch._types.SortOrder
import co.elastic.clients.elasticsearch._types.aggregations.Aggregation
import co.elastic.clients.elasticsearch._types.query_dsl.Operator
import co.elastic.clients.elasticsearch._types.query_dsl.Query
import co.elastic.clients.elasticsearch.core.SearchRequest
import co.elastic.clients.elasticsearch.core.SearchResponse
import co.elastic.clients.elasticsearch.core.search.Hit
import co.elastic.clients.elasticsearch.core.search.HitsMetadata
import grails.core.GrailsApplication
import grails.core.support.GrailsApplicationAware
import grails.persistence.support.PersistenceContextInterceptor
import grails.plugins.elasticsearch.index.IndexRequestQueue
import grails.plugins.elasticsearch.mapping.SearchableClassMapping
import grails.plugins.elasticsearch.util.GXContentBuilder
import groovy.json.JsonOutput
import groovy.util.logging.Slf4j
import org.bson.types.MinKey

@Slf4j
class ElasticSearchService implements GrailsApplicationAware {

    private static final int INDEX_REQUEST = 0
    private static final int DELETE_REQUEST = 1
    static final String MONGO_DATABASE = "mongoDatastore"

    GrailsApplication grailsApplication
    ElasticSearchHelper elasticSearchHelper
    def domainInstancesRebuilder
    ElasticSearchContextHolder elasticSearchContextHolder
    IndexRequestQueue indexRequestQueue
    PersistenceContextInterceptor persistenceInterceptor

    static transactional = false

    /**
     * Global search using Query DSL builder.
     *
     * @param params Search parameters
     * @param closure Query closure
     * @param filter The search filter, whether a Closure or a QueryBuilder
     * @param aggregation The search results aggregations, whether a Closure, a BaseAggregationBuilder or a Collection<QueryBuilder>
     */
    ElasticSearchResult search(Map params, Closure query, filter = null, aggregation = null) {
        SearchRequest request = buildSearchRequest(query, filter, aggregation, params)
        search(request, params)
    }

    /**
     * Alias for the search(Map params, Closure query) signature.
     *
     * @param query Query closure
     * @param filter The search filter, whether a Closure or a Query
     * @param aggregation The search results aggregations, whether a Closure, a Aggregation or a Collection<Query>
     * @param params Search parameters
     * @return A ElasticSearchResult containing the search results
     */
    ElasticSearchResult search(Closure query, filter = null, aggregation = null, Map params = [:]) {
        search(params, query, filter, aggregation)
    }

    /**
     *
     * @param query Query closure
     * @param aggregation The search results aggregations, whether a Closure, a BaseAggregationBuilder or a Collection<QueryBuilder>
     * @param params Search parameters
     * @return A ElasticSearchResult containing the search results
     */
    ElasticSearchResult search(Closure query, aggregation = null, Map params) {
        search(params, query, null, aggregation)
    }

    /**
     * Alias for the search(Map params, QueryBuilder query, Closure filter) signature
     *
     * @param params Search parameters
     * @param query Query query
     * @param filter The search filter, whether a Closure or a Query
     * @param aggregation The search results aggregations, whether a Closure, a Aggregation or a Collection<Query>
     * @return A ElasticSearchResult containing the search results
     */
    ElasticSearchResult search(Query query, filter = null, aggregation = null, Map params = [:]) {
        search(params, query, filter, aggregation)
    }

    /**
     * Alias for the search(Map params, Query query, Closure filter) signature
     *
     * @param params Search parameters
     * @param query Query query
     * @param filter The search filter, whether a Closure or a Query
     * @param aggregation The search results aggregations, whether a Closure, a Aggregation or a Collection<Query>
     * @return A ElasticSearchResult containing the search results
     */
    ElasticSearchResult search(Map params, Query query, filter = null, aggregation = null) {
        SearchRequest request = buildSearchRequest(query, filter, aggregation, params)
        search(request, params)
    }

    /**
     * Global search with a text query.
     *
     * @param query The search query. Will be parsed by the Lucene Query Parser.
     * @param params Search parameters
     * @return A ElasticSearchResult containing the search results
     */
    ElasticSearchResult search(String query, Map params = [:]) {
        SearchRequest request = buildSearchRequest(query, null, null, params)
        search(request, params)
    }

    /**
     * Global search with a text query.
     *
     * @param query The search query. Will be parsed by the Lucene Query Parser.
     * @param params Search parameters
     * @param filter The search filter, whether a Closure or a QueryBuilder
     * @param aggregation The search results aggregations, whether a Closure or a BaseAggregationBuilder
     * @return A ElasticSearchResult containing the search results
     */
    ElasticSearchResult search(String query, filter, aggregation = null, Map params = [:]) {
        SearchRequest request = buildSearchRequest(query, filter, aggregation, params)
        search(request, params)
    }

    /**
     * Returns the number of hits for a peculiar query
     *
     * @param query
     * @param params
     * @return An Integer representing the number of hits for the query
     */
    Integer countHits(String query, Map params = [:]) {
        SearchRequest request = buildCountRequest(query, params)
        count(request, params)
    }

    /**
     * Returns the number of hits for a peculiar query
     *
     * @param query
     * @param params
     * @return An Integer representing the number of hits for the query
     */
    Integer countHits(Map params, Closure query) {
        SearchRequest request = buildCountRequest(query, params)
        count(request, params)
    }

    /**
     * Returns the number of hits for a peculiar query
     *
     * @param query
     * @param params
     * @return An Integer representing the number of hits for the query
     */
    Integer countHits(Closure query, Map params = [:]) {
        countHits(params, query)
    }

    /**
     * Indexes all searchable instances of the specified class.
     * If call without arguments, index ALL searchable instances.
     * Note: The indexRequestQueue is using the bulk API so it is optimized.
     * Todo: should be used along with serializable IDs, but we have to deal with composite IDs beforehand
     *
     * @param options indexing options
     */
    void index(Map options) {
        doBulkRequest(options, INDEX_REQUEST)
    }

    /**
     * An alias for index(class:[MyClass1, MyClass2])
     *
     * @param domainClass List of searchable class
     */
    void index(Class... domainClass) {
        index(class: (domainClass as Collection<Class>))
    }

    /**
     * Indexes domain class instances
     *
     * @param instances A Collection of searchable instances to index
     */
    void index(Collection<GroovyObject> instances) {
        doBulkRequest(instances, INDEX_REQUEST)
    }

    /**
     * Alias for index(Object instances)
     *
     * @param instances
     */
    void index(GroovyObject... instances) {
        index(instances as Collection<GroovyObject>)
    }

    /**
     * Unindexes all searchable instances of the specified class.
     * If call without arguments, unindex ALL searchable instances.
     * Note: The indexRequestQueue is using the bulk API so it is optimized.
     * Todo: should be used along with serializable IDs, but we have to deal with composite IDs beforehand
     *
     * @param options indexing options
     */
    void unindex(Map options) {
        doBulkRequest(options, DELETE_REQUEST)
    }

    /**
     * An alias for unindex(class:[MyClass1, MyClass2])
     *
     * @param domainClass List of searchable class
     */
    void unindex(Class... domainClass) {
        unindex(class: (domainClass as Collection<Class>))
    }

    /**
     * Unindexes domain class instances
     *
     * @param instances A Collection of searchable instances to index
     */
    void unindex(Collection<GroovyObject> instances) {
        doBulkRequest(instances, DELETE_REQUEST)
    }

    /**
     * Alias for unindex(Object instances)
     *
     * @param instances
     */
    void unindex(GroovyObject... instances) {
        unindex(instances as Collection<GroovyObject>)
    }

    /**
     * Computes a bulk operation on class level.
     *
     * @param options The request options
     * @param operationType The type of the operation (INDEX_REQUEST, DELETE_REQUEST)
     * @return
     */
    private doBulkRequest(Map options, int operationType) {
        def clazz = options.class
        List<SearchableClassMapping> mappings = []
        if (clazz) {
            if (clazz instanceof Collection) {
                clazz.each { c ->
                    mappings << elasticSearchContextHolder.getMappingContextByType(c as Class)
                }
            } else {
                mappings << elasticSearchContextHolder.getMappingContextByType(clazz as Class)
            }

        } else {
            mappings = elasticSearchContextHolder.mapping.values() as List
        }
        int max = elasticSearchContextHolder.config.maxBulkRequest ?: 500

        mappings.each { scm ->
            Class<?> domainClass = scm.domainClass.type
            if (scm.root) {
                // how many needs indexing - needed to compute the page size that can be executed concurrently
                int total = domainClass.count()
                log.debug "Found $total instances of $domainClass"

                if (total > 0) {
                    // compute the number of rounds
                    int rounds = Math.ceil(total / max) as int
                    log.debug "Maximum entries allowed in each bulk request is $max, so indexing is split to $rounds iterations"

                    // Couldn't get to work with hibernate due to lost/closed hibernate session errors
                    /*GParsPool.withPool(Runtime.getRuntime().availableProcessors()) {
                        long offset = 0L
                        (1..rounds).each { round ->
                            try {
                                log.debug("Bulk index iteration $round: fetching $max results starting from ${offset}")
                                persistenceInterceptor.init()
                                persistenceInterceptor.setReadOnly()

                                //List<Class<?>> results = domainClass.listOrderById([offset: offset, max: max, order: "asc"])
                                List<Class<?>> results = domainClass.listOrderById([offset: offset, max: max, readOnly: true, sort: 'id', order: "asc"])

                                // set lastId for next run
                                offset = round * max

                                // build blocks of 100s and index them in parallel
                                results.collate(100).eachParallel { List<Map> entries ->
                                    entries.each { def entry ->
                                        if (operationType == INDEX_REQUEST) {
                                            indexRequestQueue.addIndexRequest(entry)
                                            log.debug("Adding the document ${entry.id} to the index request queue")
                                        } else if (operationType == DELETE_REQUEST) {
                                            indexRequestQueue.addDeleteRequest(entry)
                                            log.debug("Adding the document ${entry.id} to the delete request queue")
                                        }
                                        indexRequestQueue.executeRequests()

                                        entry = null
                                    }
                                    entries = null
                                }

                                persistenceInterceptor.flush()
                                persistenceInterceptor.clear()
                                persistenceInterceptor.reconnect()
                                results = null
                                log.info "Request iteration $round out of $rounds finished"
                            } finally {
                                persistenceInterceptor.flush()
                                persistenceInterceptor.clear()
                                persistenceInterceptor.destroy()
                            }
                        }
                    }*/
                    long offset = 0L
                    def id = new MinKey()
                    (1..rounds).each { round ->
                        try {
                            log.debug("Bulk index iteration $round: fetching $max results starting from ${offset}")
                            persistenceInterceptor.init()
                            persistenceInterceptor.setReadOnly()

                            List<Class<?>> results
                            switch (grailsApplication.config.elasticsearch.datastoreImpl) {
                                case MONGO_DATABASE:
                                    results = domainClass.createCriteria().list([offset: 0, max: max, readOnly: true, sort: 'id', order: "asc"]) {
                                        gt 'id', id
                                    }
                                    break
                                default:
                                    results = domainClass.listOrderById([offset: offset, max: max, readOnly: true, sort: 'id', order: "asc"])
                                    break
                            }

                            // set lastId for next run
                            offset = round * max

                            // build blocks of 100s and index them in parallel
                            results.each { def entry ->
                                if (operationType == INDEX_REQUEST) {
                                    indexRequestQueue.addIndexRequest(entry)
                                    log.debug("Adding the document ${entry.id} to the index request queue")
                                } else if (operationType == DELETE_REQUEST) {
                                    indexRequestQueue.addDeleteRequest(entry)
                                    log.debug("Adding the document ${entry.id} to the delete request queue")
                                }
                                id = entry.id
                            }
                            indexRequestQueue.executeRequests()

                            persistenceInterceptor.flush()
                            persistenceInterceptor.clear()
                            persistenceInterceptor.reconnect()
                            results = null
                            log.info "Request iteration $round out of $rounds finished"
                        } finally {
                            persistenceInterceptor.flush()
                            persistenceInterceptor.clear()
                            persistenceInterceptor.destroy()
                        }
                    }
                }


                /*if (operationType == INDEX_REQUEST) {
                     log.debug("Indexing all instances of $domainClass")
                 } else if (operationType == DELETE_REQUEST) {
                     log.debug("Deleting all instances of $domainClass")
                 }

                 // The index is split to avoid out of memory exception
                 def count = domainClass.count() ?: 0
                 log.debug("Found $count instances of $domainClass")

                 int nbRun = Math.ceil(count / max) as int

                 log.debug("Maximum entries allowed in each bulk request is $max, so indexing is split to $nbRun iterations")

                 for (int i = 0; i < nbRun; i++) {

                     int offset = i * max

                     log.debug("Bulk index iteration ${i + 1}: fetching $max results starting from ${offset}")
                     long maxId = offset + max
                     List<Class<?>> results = domainClass.findAllByIdBetween(offset, maxId, [sort: 'id', order: 'asc'])

                     log.debug("Bulk index iteration ${i + 1}: found ${results.size()} results")
                     results.each {
                         if (operationType == INDEX_REQUEST) {
                             indexRequestQueue.addIndexRequest(it)
                             log.debug("Adding the document ${it.id} to the index request queue")
                         } else if (operationType == DELETE_REQUEST) {
                             indexRequestQueue.addDeleteRequest(it)
                             log.debug("Adding the document ${it.id} to the delete request queue")
                         }
                     }
                     indexRequestQueue.executeRequests()

                     log.info("Request iteration ${i + 1} out of $nbRun finished")
                 }*/
            } else {
                log.debug("$domainClass is not a root searchable class and has been ignored.")
            }
        }
    }

    /**
     * Computes a bulk operation on instance level.
     *
     * @param instances The instance related to the operation
     * @param operationType The type of the operation (INDEX_REQUEST, DELETE_REQUEST)
     * @return
     */
    private void doBulkRequest(Collection<GroovyObject> instances, int operationType) {
        instances.each {
            def scm = elasticSearchContextHolder.getMappingContextByObject(it)
            if (scm && scm.root) {
                if (operationType == INDEX_REQUEST) {
                    indexRequestQueue.addIndexRequest(it)
                } else if (operationType == DELETE_REQUEST) {
                    indexRequestQueue.addDeleteRequest(it)
                }
            } else {
                log.debug("${it.class} is not searchable or not a root searchable class and has been ignored.")
            }
        }
        indexRequestQueue.executeRequests()
    }

    /**
     * Builds a count request
     * @param query
     * @param params
     * @return
     */
    private SearchRequest buildCountRequest(query, Map params) {
        params['size'] = 0
        return buildSearchRequest(query, null, null, params)
    }

    /**
     * Builds a search request
     *
     * @param params The query parameters
     * @param query The search query, whether a String or a Closure
     * @param filter The search filter, whether a Closure or a QueryBuilder
     * @param aggregation The search results aggregations, whether a Closure, a BaseAggregationBuilder or a Collection<QueryBuilder>
     * @return The SearchRequest instance
     */
    private SearchRequest buildSearchRequest(query, filter, aggregation, Map params) {
        SearchRequest.Builder source = new SearchRequest.Builder()
        // TODO review
        log.debug("Build search request with params: ${params}")
        source.from(params.from ? params.from as int : 0)
                .size(params.size ? params.size as int : 60)
                .minScore(params.min_score ? params.min_score as Double : 0.0)

        if (params.sort) {
            def sorters = (params.sort instanceof Collection) ? params.sort : [params.sort]

            sorters.each {
                if (it instanceof SortOptions) {
                    source.sort(it as SortOptions)
                } else {
                    source.sort(s -> s
                            .field(f -> f
                                    .field(it as String)
                                    .order(params.order?.toUpperCase() == "DESC" ? SortOrder.Desc : SortOrder.Asc)
                            )
                    )
                }
            }
        }

        // Handle the query, can either be a closure or a string
        if (query) {
            setQueryInSource(source, query, params)
        }

        if (filter) {
            setFilterInSource(source, filter, params)
        }

        if (aggregation) {
            setAggregationInSource(source, aggregation, params)
        }

        // Handle highlighting
        Closure highlight = params.highlight as Closure
        if (highlight) {
            source.highlight(h -> h.withJson(new StringReader(JsonOutput.toJson(new GXContentBuilder().build(highlight)))))
        }

        source.explain(false)

        resolveIndices(source, params)

        return source.build()
    }

    void setQueryInSource(SearchRequest.Builder source, String query, Map params = [:]) {
        Operator defaultOperator = params['default_operator'] as Operator ?: Operator.And
        source.query(q -> q
                .queryString(qs -> qs
                        .query(query)
                        .defaultOperator(defaultOperator)
                        .analyzer(params.analyzer as String)
                )
        )
    }

    void setQueryInSource(SearchRequest.Builder source, Closure query, Map params = [:]) {
        def queryBytes = new GXContentBuilder().buildAsBytes(query)
        source.query(q -> q.withJson(new ByteArrayInputStream(queryBytes)))
    }

    void setQueryInSource(SearchRequest.Builder source, Query query, Map params = [:]) {
        source.query(query)
    }

    void setAggregationInSource(SearchRequest.Builder source, Map aggregation, Map params = [:]) {
        aggregation.each { String name, Object value ->
            if (value instanceof Aggregation) {
                source.aggregations(name, value as Aggregation)
            } else {
                source.aggregations(name, a -> a.withJson(new StringReader(JsonOutput.toJson(value))))
            }
        }
    }

    void setAggregationInSource(SearchRequest.Builder source, Closure aggregation, Map params = [:]) {
        def aggregationMap = new GXContentBuilder().build(aggregation)
        setAggregationInSource(source, aggregationMap, params)
    }

    void setAggregationInSource(SearchRequest.Builder source, Aggregation aggregationBuilder, Map params = [:]) {
        source.aggregations("aggregation", aggregationBuilder)
    }

    void setAggregationInSource(SearchRequest.Builder source, Collection<Aggregation> aggregationBuilder, Map params = [:]) {
        aggregationBuilder.eachWithIndex { agg, index ->
            source.aggregations("aggregation${index}", agg)
        }
    }

    void setFilterInSource(SearchRequest.Builder source, Closure filter, Map params = [:]) {
        def filterBytes = new GXContentBuilder().buildAsBytes(filter)
        source.postFilter(f -> f.withJson(new ByteArrayInputStream(filterBytes)))
    }

    void setFilterInSource(SearchRequest.Builder source, Query filter, Map params = [:]) {
        source.postFilter(filter)
    }

    /**
     * Computes a search request and builds the results
     *
     * @param request The SearchRequest to compute
     * @param params Search parameters
     * @return A Map containing the search results
     */
    def search(SearchRequest request, Map params) {
        elasticSearchHelper.withElasticSearch { ElasticsearchClient client ->
            log.debug 'Executing search request.'
            log.debug(request.toString())
            SearchResponse<Object> searchResponse = client.search(request, Object)
            log.debug 'Completed search request.'
            log.debug(searchResponse.toString())
            HitsMetadata<Object> searchHits = searchResponse.hits()
            ElasticSearchResult result = new ElasticSearchResult()
            result.total = searchHits.total()

            log.debug "Search returned ${result.total.value() ?: 0} result(s)."

            // Convert the hits back to their initial type
            result.searchResults = domainInstancesRebuilder.buildResults(searchHits)

            // Extract highlight information.
            // Right now simply give away raw results...
            if (params.highlight) {
                for (Hit hit : searchHits.hits()) {
                    result.highlight << hit.highlight()
                }
            }

            log.debug 'Adding score information to results.'

            //Extract score information
            //Records a map from hits of (hit.id, hit.score) returned in 'scores'
            if (params.score) {
                for (Hit hit : searchHits.hits()) {
                    result.scores[(hit.id())] = hit.score()
                }
            }

            if (params.sort) {
                searchHits.hits().each { Hit hit ->
                    result.sort[hit.id()] = hit.sort().collect {
                        unwrapSortValue(it)
                    }
                }
            }

            def aggregations = searchResponse.aggregations()
            if (aggregations) {
                result.aggregations = aggregations
            }

            result
        }
    }

    /**
     * Computes a count request and returns the results
     *
     * @param request
     * @param params
     * @return Integer The number of hits for the query
     */
    Long count(SearchRequest request, Map params) {
        def result = search(request, params)
        result.total.value()
    }
    /**
     * Sets the indices on SearchRequest
     *
     * @param builder
     * @param params
     * @return
     */
    private void resolveIndices(SearchRequest.Builder builder, Map params) {
        // Handle the indices.
        if (params.indices) {
            def indices
            if (params.indices instanceof String) {
                // Shortcut for using 1 index only (not a list of values)
                indices = [params.indices.toLowerCase()]
            } else if (params.indices instanceof Class) {
                // Resolved with the class type
                SearchableClassMapping scm = elasticSearchContextHolder.getMappingContextByType(params.indices)
                indices = [scm.queryingIndex]
            } else if (params.indices instanceof Collection<Class>) {
                indices = params.indices.collect { c ->
                    SearchableClassMapping scm = elasticSearchContextHolder.getMappingContextByType(c)
                    scm.queryingIndex
                }
            }
            builder.index((indices ?: params.indices) as List<String>)
        } else {
            builder.index("_all")
        }
    }

    def unwrapSortValue(FieldValue fieldValue) {
        switch (fieldValue._kind()) {
            case FieldValue.Kind.Double:
                return fieldValue.doubleValue()
            case FieldValue.Kind.Long:
                return fieldValue.longValue()
            case FieldValue.Kind.Boolean:
                return fieldValue.booleanValue()
            case FieldValue.Kind.String:
                return fieldValue.stringValue()
            case FieldValue.Kind.Null:
                return null
            case FieldValue.Kind.Any:
                return fieldValue.anyValue()
            default:
                throw new IllegalArgumentException("Unknown fieldValue kind ${fieldValue._kind()}")
        }
    }
}
