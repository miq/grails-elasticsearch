package grails.plugins.elasticsearch

import co.elastic.clients.elasticsearch._types.aggregations.Aggregate
import co.elastic.clients.elasticsearch.core.search.HighlightField
import co.elastic.clients.elasticsearch.core.search.TotalHits
import groovy.transform.CompileStatic

@CompileStatic
class ElasticSearchResult {
    TotalHits total
    List searchResults = []
    List<Map<String, List<String>>> highlight = []
    Map<String, Double> scores = [:]
    Map<String, List<String>> sort = [:]
    Map<String, Aggregate> aggregations = [:]
}
