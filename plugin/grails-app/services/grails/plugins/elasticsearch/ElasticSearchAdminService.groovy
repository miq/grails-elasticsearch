package grails.plugins.elasticsearch

import co.elastic.clients.elasticsearch.ElasticsearchClient
import co.elastic.clients.elasticsearch._types.HealthStatus
import co.elastic.clients.elasticsearch.cluster.HealthRequest
import co.elastic.clients.elasticsearch.core.DeleteByQueryRequest
import co.elastic.clients.elasticsearch.indices.*
import grails.plugins.elasticsearch.index.IndexRequestQueue
import grails.plugins.elasticsearch.mapping.SearchableClassMapping
import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import java.util.regex.Matcher

class ElasticSearchAdminService {

    static transactional = false

    static final Logger LOG = LoggerFactory.getLogger(this)

    ElasticSearchHelper elasticSearchHelper
    ElasticSearchContextHolder elasticSearchContextHolder
    IndexRequestQueue indexRequestQueue
    JsonSlurper jsonSlurper = new JsonSlurper()

    private static final WAIT_FOR_INDEX_MAX_RETRIES = 10
    private static final WAIT_FOR_INDEX_SLEEP_INTERVAL = 100

    /**
     * Explicitly refresh one or more index, making all operations performed since the last refresh available for search
     * This method will also flush all pending request in the indexRequestQueue and will wait for their completion.
     * @param indices The indices to refresh. If null, will refresh ALL indices.
     */
    void refresh(List<String> indices = null) {
        // Flush any pending operation from the index queue
        indexRequestQueue.executeRequests()

        // Refresh ES
        elasticSearchHelper.withElasticSearch { ElasticsearchClient client ->
            RefreshResponse response = client.indices().refresh(RefreshRequest.of(b -> !indices ? b.index('_all') : b.index(indices)))

            if (response.shards().failed() > 0) {
                LOG.info "Refresh failure"
            } else {
                LOG.info "Refreshed ${indices ?: 'all'} indices"
            }
        }
    }

    /**
     * Explicitly refresh one or more index, making all operations performed since the last refresh available for search
     * This method will also flush all pending request in the indexRequestQueue and will wait for their completion.
     * @param indices The indices to refresh. If null, will refresh ALL indices.
     */
    void refresh(String... indices) {
        refresh(indices as List<String>)
    }

    /**
     * Explicitly refresh ALL index, making all operations performed since the last refresh available for search
     * This method will also flush all pending request in the indexRequestQueue and will wait for their completion.
     * @param searchableClasses The indices represented by the specified searchable classes to refresh. If null, will refresh ALL indices.
     */
    void refresh(Class... searchableClasses) {
        List<String> toRefresh = []

        // Retrieve indices to refresh
        searchableClasses.each {
            SearchableClassMapping scm = elasticSearchContextHolder.getMappingContextByType(it)
            if (scm) {
                toRefresh << scm.queryingIndex
                toRefresh << scm.indexingIndex
            }
        }

        refresh(toRefresh.unique())
    }

    /**
     * Delete one or more index and all its data.
     * @param indices The indices to delete. If null, will delete ALL indices.
     */
    void deleteIndex(List<String> indices = null) {
        elasticSearchHelper.withElasticSearch { ElasticsearchClient client ->
            if (!indices) {
                client.indices().delete(DeleteIndexRequest.of(b -> b.index("_all")))
                LOG.info "Deleted all indices"
            } else {
                client.indices().delete(DeleteIndexRequest.of(b -> b.index(indices)))
                LOG.info "Deleted indices $indices"
            }
        }
    }

    /**
     * Deletes all documents from one.
     * @param aliasIndexName The alias index name from which to delete the documents.
     */
    void deleteAllDocumentsFromIndex(String aliasIndexName) {
        String indexName = indexNameByAlias(aliasIndexName)
        elasticSearchHelper.withElasticSearch { ElasticsearchClient client ->
            client.deleteByQuery(DeleteByQueryRequest.of(b -> b
                    .index(indexName)
                    .query(q -> q.matchAll(m -> m))
            ))
            LOG.info "Deleted all documents from $aliasIndexName"
        }
    }

    /**
     * Delete one or more index and all its data.
     * @param indices The indices to delete. If null, will delete ALL indices.
     */
    void deleteIndex(String... indices) {
        deleteIndex(indices as List<String>)
    }

    /**
     * Delete one or more index and all its data.
     * @param indices The indices to delete in the form of searchable class(es).
     */
    void deleteIndex(Class... searchableClasses) {
        List<String> toDelete = []

        // Retrieve indices to delete
        searchableClasses.each {
            SearchableClassMapping scm = elasticSearchContextHolder.getMappingContextByType(it)
            if (scm) {
                toDelete << scm.indexName
            }
        }
        // We do not trigger the deleteIndex with an empty list as it would delete ALL indices.
        // If toDelete is empty, it might be because of a misuse of a Class the user thought to be a searchable class
        if (!toDelete.isEmpty()) {
            deleteIndex(toDelete.unique())
        }
    }

    /**
     * Creates mappings on a type
     * @param index The index where the mapping is being created
     * @param type The type where the mapping is created
     * @param elasticMapping The mapping definition
     */
    void createMapping(String index, String type, Map<String, Object> elasticMapping) {
        LOG.info("Creating Elasticsearch mapping for ${index} and type ${type} ...")
        elasticSearchHelper.withElasticSearch { ElasticsearchClient client ->
            // TODO: after simplification of elasticMappings structure, simplify this too!
            Map<String, Object> mappingBody
            if (elasticMapping.containsKey("properties")) {
                mappingBody = elasticMapping
            } else if (elasticMapping.containsKey(type) && (elasticMapping.get(type) instanceof Map) && ((Map) elasticMapping.get(type)).containsKey("properties")) {
                mappingBody = (Map<String, Object>) elasticMapping.get(type)
            } else {
                mappingBody = [properties: elasticMapping]
            }

            client.indices().putMapping(
                    PutMappingRequest.of(builder -> builder
                            .index(index)
                            .withJson(new StringReader(JsonOutput.toJson(mappingBody)))
                    )
            )
        }
    }

    /**
     * Check whether a mapping exists
     * @param index The name of the index to check on
     * @param type The type which mapping is being checked
     * @return true if the mapping exists
     */
    boolean mappingExists(String index, String type) {
        elasticSearchHelper.withElasticSearch { ElasticsearchClient client ->
            try {
                GetMappingResponse response = client.indices().getMapping(GetMappingRequest.of(b -> b.index(index)))
                return !response.result().isEmpty()
            } catch (Exception e) {
                return false
            }
        }
    }

    /**
     * Deletes a version of an index
     * @param index The name of the index
     * @param version the version number, if provided <index>_v<version> will be used
     */
    void deleteIndex(String index, Integer version = null) {
        index = versionIndex index, version
        LOG.info("Deleting  Elasticsearch index ${index} ...")
        elasticSearchHelper.withElasticSearch { ElasticsearchClient client ->
            client.indices().delete(DeleteIndexRequest.of(b -> b.index(index)))
        }
    }

    /**
     * Creates a new index
     * @param index The name of the index
     * @param settings The index settings (ie. number of shards)
     */
    void createIndex(String index, Map settings=null, Map<String, Map> esMappings = [:]) {
        LOG.debug "Creating index ${index} ..."

        elasticSearchHelper.withElasticSearch { ElasticsearchClient client ->
            CreateIndexRequest request = CreateIndexRequest.of(builder -> {
                builder.index(index)
                if (esMappings) {
                    // Combine all mappings into one TypeMapping (ES8 supports only one)
                    Map<String, Object> combinedProperties = [:]
                    esMappings.each { type, mapping ->
                        if (mapping.containsKey("properties")) {
                            combinedProperties.putAll((Map) mapping.properties)
                        } else if (mapping.containsKey(type) && (mapping.get(type) instanceof Map) && ((Map) mapping.get(type)).containsKey("properties")) {
                            combinedProperties.putAll((Map) ((Map) mapping.get(type)).properties)
                        } else {
                            combinedProperties.putAll(mapping)
                        }
                    }
                    Map<String, Object> combinedMapping = [properties: combinedProperties]
                    builder.mappings(m -> m.withJson(new StringReader(JsonOutput.toJson(combinedMapping))))
                }
                if (settings) {
                    builder.settings(s -> s.withJson(new StringReader(JsonOutput.toJson(settings))))
                    return builder
                }
            })
            client.indices().create(request)
        }
    }

    /**
     * Creates a new index
     * @param index The name of the index
     * @param version the version number, if provided <index>_v<version> will be used
     * @param settings The index settings (ie. number of shards)
     */
    void createIndex(String index, Integer version, Map settings=null, Map<String, Map> esMappings = [:]) {
        index = versionIndex(index, version)
        createIndex(index, settings, esMappings)
    }

    /**
     * Checks whether the index exists
     * @param indexName The name of the index
     * @param version the version number, if provided <index>_v<version> will be used
     * @return true, if the index exists
     */
    boolean indexExists(String indexName, Integer version = null) {
        indexName = versionIndex(indexName, version)
        elasticSearchHelper.withElasticSearch { ElasticsearchClient client ->
            ExistsRequest request = ExistsRequest.of(e -> e.index(indexName))
//            request.humanReadable(true)
            client.indices().exists(request).value()
        }
    }

    /**
     * Waits for the specified version of the index to exist
     * @param index The name of the index
     * @param version the version number
     */
    void waitForIndex(String index, int version) {
        int retries = WAIT_FOR_INDEX_MAX_RETRIES
        while (getLatestVersion(index) < version && retries--) {
            LOG.debug("Index ${versionIndex(index, version)} not found, sleeping for ${WAIT_FOR_INDEX_SLEEP_INTERVAL}...")
            Thread.sleep(WAIT_FOR_INDEX_SLEEP_INTERVAL)
        }
    }

    /**
     * Returns the name of the index pointed by an alias
     *
     * @param alias The alias to be checked
     * @return the name of the index
     */
    String indexPointedBy(String alias) {
        elasticSearchHelper.withElasticSearch { ElasticsearchClient client ->
            // TODO: here we fetch all aliases because, if we only fetch the ones with our given name, we could get an
            //       exception with a 404 error. Maybe it would we better to handle the exception than search through all
            //       aliases
            def aliasesResponse = client.indices().alias
            def indexName = aliasesResponse.result().entrySet().find {
                alias in it.value.aliases().keySet()
            }?.key
            // No index pointed to was found, maybe we got an index name instead of an alias?
            if (!indexName && aliasesResponse.result().containsKey(alias)) {
                return alias
            }
            return indexName
        }
    }

    /**
     * Deletes an alias pointing to an index
     * @param alias The name of the alias
     */
    void deleteAlias(String alias) {
        elasticSearchHelper.withElasticSearch { ElasticsearchClient client ->
            String indexName = indexPointedBy(alias)
            if (indexName) {
                client.indices().updateAliases(UpdateAliasesRequest.of(b -> b
                        .actions(a -> a
                                .remove(r -> r.index(indexName).alias(alias))
                        )
                ))
            }
        }
    }

    /**
     * Makes an alias point to a new index, removing the relationship with a previous index, if any
     * @param alias the alias to be created/modified
     * @param index the index to be pointed to
     * @param version the version number, if provided <index>_v<version> will be used
     */
    void pointAliasTo(String alias, String index, Integer version = null) {
        def versionedIndex = versionIndex(index, version)
        LOG.debug "Creating alias ${alias}, pointing to index ${versionedIndex} ..."
        String oldIndex = indexPointedBy(alias)
        elasticSearchHelper.withElasticSearch { ElasticsearchClient client ->
            if (oldIndex && oldIndex != versionedIndex) {
                LOG.debug "Index used to point to ${oldIndex}, removing ..."
                client.indices().deleteAlias(DeleteAliasRequest.of(b -> b.index(oldIndex).name(alias)))
            }
            LOG.error "Create alias -> index: ${versionedIndex}; alias: ${alias}"
            client.indices().putAlias(PutAliasRequest.of(b -> b.index(versionedIndex).name(alias)))
        }
    }

    /**
     * Checks whether an alias exists
     * @param alias the name of the alias
     * @return true if the alias exists
     */
    boolean aliasExists(String alias) {
        elasticSearchHelper.withElasticSearch { ElasticsearchClient client ->
            client.indices().existsAlias(ExistsAliasRequest.of { b -> b.name(alias) }).value()
        }
    }

    /**
     * Returns the index name by the given alias
     * @param alias the name of the alias
     * @return i if the index name if exists
     */
    // TODO: check if in what cases this differs to indexPointedBy(alias)
    String indexNameByAlias(String alias) {
        return indexPointedBy(alias)
    }

    /**
     * Builds an index name based on a base index and a version number
     * @param index
     * @param version
     * @return <index>_v<version> if version is provided, <index> otherwise
     */
    String versionIndex(String index, Integer version = null) {
        version == null ? index : index + "_v${version}"
    }

    /**
     * Returns all the indices
     *
     * @return a Set of index names
     */
    Set<String> getIndices() {
        elasticSearchHelper.withElasticSearch { ElasticsearchClient client ->
            return client.indices().alias.result().keySet().findAll { indexName -> !indexName.startsWith('.')}
        }
    }

    /**
     * Returns all the indices starting with a prefix
     * @param prefix the prefix
     * @return a Set of index names
     */
    Set<String> getIndices(String prefix) {
        Set indices = getIndices()
        if (prefix) {
            indices = indices.findAll {
                it =~ /^${prefix}/
            }
        }
        indices
    }

    /**
     * The current version of the index
     * @param index
     * @return the current version if any exists, -1 otherwise
     */
    int getLatestVersion(String index) {
        def versions = getIndices(index).collect {
            Matcher m = (it =~ /^${index}_v(\d+)$/)
            m ? m[0][1] as Integer : -1
        }.sort()
        versions ? versions.last() : -1
    }

    /**
     * The next available version for an index
     * @param index the index name
     * @return an integer representing the next version to be used for this index (ie. 10 if the latest is <index>_v<9>)
     */
    int getNextVersion(String index) {
        getLatestVersion(index) + 1
    }

    /**
     * Waits for the cluster to be on Yellow status
     */
    void waitForClusterStatus(HealthStatus status = HealthStatus.Yellow) {
        elasticSearchHelper.withElasticSearch { ElasticsearchClient client ->
            def request = HealthRequest.of(b -> b.waitForStatus(status).timeout((t -> t.time("30s"))))
            def response = client.cluster().health(request)

            LOG.debug("Cluster status: ${response.status()}")
        }
    }
}
