package grails.plugins.elasticsearch.conversion.unmarshall

import co.elastic.clients.elasticsearch.core.search.Hit
import co.elastic.clients.elasticsearch.core.search.HitsMetadata
import co.elastic.clients.elasticsearch.core.search.TotalHitsRelation
import grails.core.GrailsApplication
import grails.gorm.transactions.Rollback
import grails.plugins.elasticsearch.ElasticSearchContextHolder
import grails.plugins.elasticsearch.ElasticSearchSpec
import grails.plugins.elasticsearch.exception.MappingException
import grails.testing.mixin.integration.Integration
import groovy.json.JsonSlurper
import spock.lang.Specification
import test.Color
import test.GeoPoint

import java.time.*

@Integration
@Rollback
class DomainClassUnmarshallerIntegrationSpec extends Specification implements ElasticSearchSpec {

    ElasticSearchContextHolder elasticSearchContextHolder
    GrailsApplication grailsApplication

    void setup() {
        resetElasticsearch()
    }

    void cleanupSpec() {
        def dataFolder = new File('data')
        if (dataFolder.isDirectory()) {
            dataFolder.deleteDir()
        }
    }

    void 'An unmarshalled geo_point is marshalled into a GeoPoint domain object'() {
        def unmarshaller = new DomainClassUnmarshaller(elasticSearchContextHolder: elasticSearchContextHolder, grailsApplication: grailsApplication)

        given: 'a search hit with a geo_point'
        def hit = Hit<GeoPoint>.of(b -> b
                .index('test.building')
                .id('1')
                .source(new JsonSlurper().parse(new StringReader('{"location":{"class":"test.GeoPoint","id":"2", "lat":53.0,"lon":10.0},"name":"WatchTower"}'))))
        def maxScore = 0.1534264087677002f
        def totalHits = 1
        def searchHits = HitsMetadata<GeoPoint>.of(b -> b
                .hits([hit])
                .total(th -> th.value(totalHits).relation(TotalHitsRelation.Eq))
                .maxScore(maxScore)
        )

        when: 'an geo_point is unmarshalled'
        def results = unmarshaller.buildResults(searchHits)
        results.size() == 1

        then: 'this results in a GeoPoint domain object'
        results[0].name == 'WatchTower'
        def location = results[0].location
        location.class == GeoPoint
        location.lat == 53.0
        location.lon == 10.0
    }

    void 'An unmarshalled temporal type is marshalled into a corresponding object'() {
        def unmarshaller = new DomainClassUnmarshaller(elasticSearchContextHolder: elasticSearchContextHolder, grailsApplication: grailsApplication)

        given: 'a search hit with some temporal types'
        def hit = Hit<Object>.of(b -> b
                .index('test.dates')
                .id('1')
                .source(new JsonSlurper().parse(new StringReader('{"date":"2019-08-12T07:25:17.935Z","localDateTime":"2019-08-12T09:25:17.935Z","zonedDateTime":"2019-08-12T03:25:17.935-04:00","offsetTime":"03:25:17.935-04:00","name":"Object with java.util.time types","offsetDateTime":"2019-08-12T03:25:17.935-04:00","localDate":"2019-08-12"}'))))
        def maxScore = 0.1534264087677002f
        def totalHits = 1
        def searchHits = HitsMetadata<Object>.of(b -> b
                .hits([hit])
                .total(th -> th.value(totalHits).relation(TotalHitsRelation.Eq))
                .maxScore(maxScore)
        )

        when: 'an java.time type is unmarshalled'
        def results = unmarshaller.buildResults(searchHits)
        results.size() == 1

        then: 'this results in a valid domain object'
        results[0].name == 'Object with java.util.time types'
        results[0].date != null && results[0].date instanceof Date
        results[0].localDate != null && results[0].localDate instanceof LocalDate
        results[0].localDateTime != null && results[0].localDateTime instanceof LocalDateTime
        results[0].zonedDateTime != null && results[0].zonedDateTime instanceof ZonedDateTime
        results[0].offsetDateTime != null && results[0].offsetDateTime instanceof OffsetDateTime
        results[0].offsetTime != null && results[0].offsetTime instanceof OffsetTime
    }

    void 'An unhandled property in the indexed domain root is ignored'() {
        def unmarshaller = new DomainClassUnmarshaller(elasticSearchContextHolder: elasticSearchContextHolder, grailsApplication: grailsApplication)

        given: 'a search hit with a color with unhandled properties r-g-b'
        def hit = Hit<Object>.of(b -> b
                .index('test.color')
                .id('1')
                .source(new JsonSlurper().parse(new StringReader('{"name":"Orange", "red":255, "green":153, "blue":0}'))))
        def maxScore = 0.1534264087677002f
        def totalHits = 1
        def searchHits = HitsMetadata<Object>.of(b -> b
                .hits([hit])
                .total(th -> th.value(totalHits).relation(TotalHitsRelation.Eq))
                .maxScore(maxScore))
        GroovySpy(MappingException, global: true)

        when: 'the color is unmarshalled'
        def results = unmarshaller.buildResults(searchHits)
        results.size() == 1

        then: 'this results in a color domain object'
        1 * new MappingException('Property Color.red found in index, but is not defined as searchable.')
        1 * new MappingException('Property Color.green found in index, but is not defined as searchable.')
        1 * new MappingException('Property Color.blue found in index, but is not defined as searchable.')
        0 * new MappingException(_ as String)
        results[0].name == 'Orange'
        results[0].red == null
        results[0].green == null
        results[0].blue == null
    }

    void 'An unhandled property in an embedded indexed domain is ignored'() {
        def unmarshaller = new DomainClassUnmarshaller(elasticSearchContextHolder: elasticSearchContextHolder, grailsApplication: grailsApplication)

        given: 'a search hit with a circle, within it a color with an unhandled properties "red"'
        def hit = Hit<Color>.of(b -> b
                .id('1')
                .index('test.circle')
                .source(new JsonSlurper().parse(new StringReader('{"radius":7, "color":{"class":"test.Color", "id":"2", "name":"Orange", "red":255}}'))))
        def maxScore = 0.1534264087677002f
        def totalHits = 1
        def searchHits = HitsMetadata<Color>.of(b -> b
                .hits([hit])
                .total(th -> th.value(totalHits).relation(TotalHitsRelation.Eq))
                .maxScore(maxScore))
        GroovySpy(MappingException, global: true)

        when: 'the circle is unmarshalled'
        def results = unmarshaller.buildResults(searchHits)
        results.size() == 1

        then: 'this results in a circle domain object with color'
        1 * new MappingException('Property Color.red found in index, but is not defined as searchable.')
        0 * new MappingException(_ as String)
        results[0].radius == 7
        def color = results[0].color
        color.name == 'Orange'
        color.red == null
        color.green == null
        color.blue == null
    }
}
