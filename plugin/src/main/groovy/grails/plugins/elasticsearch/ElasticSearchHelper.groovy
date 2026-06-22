package grails.plugins.elasticsearch

import co.elastic.clients.elasticsearch.ElasticsearchClient
import groovy.transform.stc.ClosureParams
import groovy.transform.stc.SimpleType

class ElasticSearchHelper {

    ElasticsearchClient elasticSearchClient

    def <R> R withElasticSearch(@ClosureParams(value=SimpleType, options="co.elastic.clients.elasticsearch.ElasticsearchClient") Closure<R> callable) {
        callable.call(elasticSearchClient)
    }
}
