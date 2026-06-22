package grails.plugins.elasticsearch

import co.elastic.clients.elasticsearch.ElasticsearchClient
import co.elastic.clients.elasticsearch._types.mapping.TypeMapping
import co.elastic.clients.elasticsearch._types.query_dsl.Query
import co.elastic.clients.elasticsearch.core.SearchRequest
import co.elastic.clients.elasticsearch.indices.GetIndexRequest
import co.elastic.clients.elasticsearch.indices.GetIndexResponse
import grails.core.GrailsApplication
import grails.plugins.elasticsearch.mapping.DomainEntity
import grails.plugins.elasticsearch.mapping.SearchableClassMappingConfigurator
import grails.util.GrailsNameUtils
import org.grails.datastore.gorm.GormEntity
import org.hibernate.SessionFactory
import org.springframework.beans.factory.annotation.Autowired

trait ElasticSearchSpec {

    @Autowired
    GrailsApplication grailsApplication

    @Autowired
    SessionFactory sessionFactory

    @Autowired
    ElasticSearchService elasticSearchService

    @Autowired
    ElasticSearchAdminService elasticSearchAdminService

    @Autowired
    ElasticSearchHelper elasticSearchHelper

    @Autowired
    SearchableClassMappingConfigurator searchableClassMappingConfigurator

    void resetElasticsearch() {
        deleteIndices()
        searchableClassMappingConfigurator.configureAndInstallMappings()
    }

    static <T> T save(GormEntity<T> object, boolean flush = true) {
        object.save(flush: flush, failOnError: true)
    }

    ElasticSearchResult search(Class<?> clazz, String query) {
        elasticSearchService.search(query, [indices: clazz, types: clazz])
    }

    ElasticSearchResult search(Class<?> clazz, Closure query) {
        elasticSearchService.search(query, [indices: clazz, types: clazz])
    }

    ElasticSearchResult search(String query, Map params = [:]) {
        elasticSearchService.search(query, params)
    }

    ElasticSearchResult search(SearchRequest request, Map params) {
        elasticSearchService.search(request, params)
    }

    ElasticSearchResult search(Class<?> clazz, Query query) {
        elasticSearchService.search([indices: clazz, types: clazz], query)
    }

    void clearSession() {
        sessionFactory.currentSession.clear()
    }

    void flushSession() {
        sessionFactory.currentSession.flush()
    }

    void refreshIndices() {
        elasticSearchAdminService.refresh()
    }

    void refreshIndex(List<String> indices) {
        elasticSearchAdminService.refresh(indices)
    }

    void index(GroovyObject... instances) {
        elasticSearchService.index(instances as Collection<GroovyObject>)
    }

    void index(Class... domainClass) {
        elasticSearchService.index(domainClass)
    }

    void unindex(GroovyObject... instances) {
        elasticSearchService.unindex(instances as Collection<GroovyObject>)
    }

    void unindex(Class... domainClass) {
        elasticSearchService.unindex(domainClass)
    }

    void refreshIndex(Class... searchableClasses) {
        elasticSearchAdminService.refresh(searchableClasses)
    }

    void deleteIndices() {
        elasticSearchAdminService.deleteIndex()
        elasticSearchAdminService.refresh()
    }

    String getIndexName(DomainEntity domainClass) {
        String name = grailsApplication.config.getProperty("elasticSearch.index.name", String) ?: domainClass.fullName
        if (!name) {
            name = domainClass.defaultPropertyName
        }
        name.toLowerCase()
    }

    String getTypeName(DomainEntity domainClass) {
        GrailsNameUtils.getPropertyName(domainClass.type)
    }

    DomainEntity getDomainClass(Class<?> clazz) {
        elasticSearchService.elasticSearchContextHolder.getMappingContextByType(clazz).domainClass
    }

    TypeMapping getFieldMappingMetaData(String indexName, String documentType) {
        if (elasticSearchAdminService.aliasExists(indexName)) {
            indexName = elasticSearchAdminService.indexPointedBy(indexName)
        }
        final String finalIndexName = indexName
        elasticSearchHelper.withElasticSearch { ElasticsearchClient client ->
            GetIndexRequest request = GetIndexRequest.of(g -> g.index(finalIndexName))
            GetIndexResponse getIndexResponse = client.indices().get(request)
            getIndexResponse.get(finalIndexName).mappings()
        }
    }

}
