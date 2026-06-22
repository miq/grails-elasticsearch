package grails.plugins.elasticsearch

import co.elastic.clients.elasticsearch.ElasticsearchClient
import grails.core.support.proxy.ProxyHandler
import grails.plugins.elasticsearch.mapping.DomainEntity
import grails.plugins.elasticsearch.mapping.SearchableClassMapping
import groovy.transform.CompileStatic
import org.grails.datastore.mapping.proxy.EntityProxy

@CompileStatic
class ElasticSearchContextHolder {

    ElasticSearchHelper elasticSearchHelper

    ProxyHandler proxyHandler

    /**
     * The configuration of the ElasticSearch plugin
     */
    ConfigObject config

    /**
     * A map containing the mapping to ElasticSearch
     */
    Map<String, SearchableClassMapping> mapping = [:]

    /**
     * A Set containing all the indices that were regenerated during migration
     */
    Set<String> indexesRebuiltOnMigration = [] as Set<String>

    /**
     * Adds a mapping context to the current mapping holder
     *
     * @param scm The SearchableClassMapping instance to add
     */
    void addMappingContext(SearchableClassMapping scm) {
        mapping[scm.domainClass.fullName] = scm
    }

    /**
     * Returns the mapping context for a peculiar type
     * @param type
     * @return
     */
    SearchableClassMapping getMappingContext(String type) {
        mapping[type]
    }

    /**
     * Returns the mapping context for a peculiar GrailsDomainClass
     * @param domainClass
     * @return
     */
    SearchableClassMapping getMappingContext(DomainEntity domainClass) {
        getMappingContextByType(domainClass.type)
    }

    /**
     * Returns the mapping context for a peculiar Class
     *
     * @param clazz
     * @return
     */
    SearchableClassMapping getMappingContextByType(Class clazz) {
        if(clazz in EntityProxy) {
            clazz = clazz.superclass
        }
        mapping.values().find { scm -> scm.domainClass.type == clazz }
    }

    SearchableClassMapping getMappingContextByObject(o) {
        Class clazz = o.class
        if(proxyHandler.isProxy(o)) {
            clazz = o.class.superclass
        }
        mapping.values().find { scm -> scm.domainClass.type == clazz }
    }

    /**
     * Determines if a Class is root-mapped by the ElasticSearch plugin
     *
     * @param clazz
     * @return A boolean determining if the class is root-mapped or not
     */
    boolean isRootClass(Class clazz) {
        if(clazz in EntityProxy) {
            clazz = clazz.superclass
        }
        mapping.values().any { scm -> scm.domainClass.type == clazz && scm.isRoot() }
    }

    /**
     * Returns the Class that is associated to a specific elasticSearch type
     *
     * @param elasticTypeName
     * @return A Class instance or NULL if the class was not found
     */
    Class findMappedClassByElasticType(String elasticTypeName) {
        findMappingContextByElasticType(elasticTypeName)?.domainClass?.type
    }

    /**
     * Returns all the Classes associated to a specific elasticSearch index
     *
     * @param elasticTypeName
     * @return A Class instance or NULL if the class was not found
     */
    List<Class> findMappedClassesOnIndices(Set<String> indices) {
        mapping.values().findAll { SearchableClassMapping scm ->
            scm.indexName in indices
        }*.domainClass*.type as List<Class>
    }

    /**
     * Returns the SearchableClassMapping that is associated to a elasticSearch type
     * @param elasticTypeName
     * @return
     */
    SearchableClassMapping findMappingContextByElasticType(String elasticTypeName) {
        mapping.values().find { scm -> scm.elasticTypeName == elasticTypeName }
    }

    /**
     * Returns the SearchableClassMapping that is associated to a elasticSearch index
     *
     * @param indexName
     * @return
     */
    SearchableClassMapping findMappingContextByIndex(String indexName) {
        def scm = mapping.values().find { scm ->
            scm.indexingIndex == indexName ||
                    scm.queryingIndex == indexName ||
                    scm.indexName == indexName
        }
        if (scm) {
            return scm
        }
        // no mapping found for index, maybe one for its aliases exists
        def aliases = aliasesOf(indexName)
        def mappingName = aliases.find { mapping.values()*.indexName.contains(it) }
        return mappingName ? mapping.values().find {it.indexName == mappingName } : null
    }


    // TODO: Document
    Set<String> aliasesOf(String indexName) {
        elasticSearchHelper.withElasticSearch { ElasticsearchClient client ->
            def aliasesResponse = client.indices().alias
            return aliasesResponse.result()[indexName].aliases().keySet()
        }
    }

}
