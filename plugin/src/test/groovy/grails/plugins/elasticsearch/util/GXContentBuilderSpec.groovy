package grails.plugins.elasticsearch.util

import groovy.json.JsonSlurper
import spock.lang.Specification

class GXContentBuilderSpec extends Specification {

    private static final Closure AGGREGATION_QUERY = {
        "types" {
            filters {
                "filters" {
                    "jpg" { match(type: 'jpg') }
                    "png" { match(type: 'png') }
                }
            }
        }

        "names" {
            terms(field: 'name')
        }

        "avg_size" {
            avg(field: 'size')
        }
    }

    private static final Map EXPECTED_AGGREGATION_QUERY_MAP = [
            types   : [filters: [filters: [jpg: [match: [type: 'jpg']], png: [match: [type: 'png']]]]],
            names   : [terms: [field: 'name']],
            avg_size: [avg: [field: 'size']]
    ]

    void "test build aggregationQuery"() {
        given:
        GXContentBuilder builder = new GXContentBuilder()

        expect:
        builder.build(AGGREGATION_QUERY).toString() == "[types:[filters:[filters:[jpg:[match:[type:jpg]], png:[match:[type:png]]]]], names:[terms:[field:name]], avg_size:[avg:[field:size]]]"
    }

    void "test buildAsString generates json"() {
        given:
        GXContentBuilder builder = new GXContentBuilder()

        expect:
        new JsonSlurper().parseText(builder.buildAsString(AGGREGATION_QUERY)) == EXPECTED_AGGREGATION_QUERY_MAP
    }

    void "test buildAsBytes generates json"() {
        given:
        GXContentBuilder builder = new GXContentBuilder()

        expect:
        new JsonSlurper().parseText(new String(builder.buildAsBytes(AGGREGATION_QUERY))) == EXPECTED_AGGREGATION_QUERY_MAP
    }
}
