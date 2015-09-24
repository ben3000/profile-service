package au.org.ala.profile

import com.google.api.client.googleapis.auth.oauth2.GoogleCredential
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport
import com.google.api.client.http.HttpTransport
import com.google.api.client.json.jackson2.JacksonFactory
import com.google.api.services.analytics.Analytics
import com.google.api.services.analytics.AnalyticsScopes
import com.google.api.services.analytics.model.GaData

/**
 * Access to Google Analytics API for eFlora.
 * <p>This service authorises itself to Google using a Service Account to permit the
 * eFlora application to interact in a server-to-server manner with the Google Analytics
 * API.
 * <p>To enable this service, visit
 * <a href="https://console.developers.google.com/">Google's Developer's Console</a> and
 * <a href="https://developers.google.com/identity/protocols/OAuth2ServiceAccount">follow
 * the instructions</a> to generate a service account. Then note the service account's
 * email address, download the service account's public/private key as a P12 file, and
 * enable the Analytics API. Finally, add the service account's email to the User Management
 * section of your Google Analytics account so it has access to the data.</p>
 * <p>Once completed, add the following items to the profile-service's config:</p>
 * <dl>
 *     <dt>{@code analytics.serviceAccountEmail}</dt>
 *     <dd>The service account's email address as noted above.</dd>
 *     <dt>{@code analytics.viewId}</dt>
 *     <dd>The viewId for the view that all queries generated by this service will use;
 *     this must point to the view that tracks profile-hub hits on profile pages
 *     <dt>{@code analytics.p12.file}</dt>
 *     <dd>The path to the service account's public/private key in P12 format. Save the
 *     file under the profile-service's config area and provide the path here
 * </dt>
 * <p><a href="https://developers.google.com/analytics/devguides/reporting/core/v3/quickstart/service-java">
 * This document</a> proved helpful in understanding the Java API for the service.</p>
 */
class AnalyticsService {

    // All time (the oldest date available is 2005-01-01, which suits us just fine)
    public static final String ALL_TIME = "2005-01-01"
    private static final JSON_FACTORY = JacksonFactory.getDefaultInstance()

    def grailsApplication
    private Analytics analytics
    private String viewIds

    boolean enabled() {
        return !"${grailsApplication.config.analytics.serviceAccountEmail}"?.isEmpty()
    }

    /*
     * We can't do this as a constructor, because grailsApplication is not
     * injected until after construction. We can't do this as a @PostConstruct method
     * because that imposes a requirement for configuration on the Travis CI build which
     * we don't want.
     */

    def init() {
        HttpTransport httpTransport = GoogleNetHttpTransport.newTrustedTransport()
        GoogleCredential credential = new GoogleCredential.Builder()
                .setTransport(httpTransport)
                .setJsonFactory(JSON_FACTORY)
                .setServiceAccountId(
                (String) grailsApplication.config.analytics.serviceAccountEmail)
                .setServiceAccountPrivateKeyFromP12File(
                new File((String) grailsApplication.config.analytics.p12.file))
                .setServiceAccountScopes(
                Collections.singleton(AnalyticsScopes.ANALYTICS_READONLY))
                .build()

        analytics = new Analytics.Builder(httpTransport, JSON_FACTORY, credential)
                .setApplicationName("${grailsApplication.config.application.name}")
                .build()

        viewIds = "ga:${grailsApplication.config.analytics.viewId}"
    }

    /**
     * Query Google Analytics for the most interesting statistics on a profile.
     * <p>Pageviews for this profile by key are:
     * <ul>
     *     <li>{@code allTime}: usage statistics for this profile since counting began,</li>
     *     <li>{@code 30Days}: usage statistics for this profile in the last 30 days,
     *     and</li>
     *     <li>{@code 7Days}: usage statistics for this profile in the last 7 days</li>
     * </ul>
     *
     * @param profile the profile of interest
     * @return a map containing the keys {@code sessions} and {@code pageviews}
     * representing those statistics accrued by this profile over the
     * periods mentioned above; <em>falsy</em> values are provided when no data could be
     * found.
     */
    Map analyticsByProfile(Profile profile) {
        Map ret = [:]

        if (!analytics) {
            init();
        }

        def opusId = profile.opus.shortName ?: profile.opus.uuid

        def profileUri = "${grailsApplication.config.contextPath ?: ''}/opus/${opusId}/profile/${profile.fullName}"

        // Query Google Analytics for ga:sessions and ga:pageviews...
        ret.allTime = queryForViews(profileUri, ALL_TIME)

        // Last 30 days
        ret.last30Days = queryForViews(profileUri, "30daysAgo")

        // Last 7 days
        ret.last7Days = queryForViews(profileUri, "7daysAgo")

        return ret
    }

    /**
     * Query Google Analytics for the most interesting summary statistics on the opus
     * @param opus the opus of interest
     * @return a map containing the keys {@code pagePath}, {@code sessions} and
     * {@code pageviews} representing those statistics for the most viewed profile in this
     * opus; <em>falsy</em> values are provided when no data could be found.
     */
    Map analyticsByOpus(Opus opus) {
        Map ret = [:]

        if (!analytics) {
            init();
        }

        Calendar from = Calendar.getInstance()
        from.set(Calendar.DAY_OF_MONTH, 1)
        from.set(Calendar.HOUR_OF_DAY, 0)
        from.set(Calendar.MINUTE, 0)
        from.set(Calendar.SECOND, 0)
        from.set(Calendar.MILLISECOND, 0)

        String fromStr = from ? from.format("yyyy-MM-dd") : ALL_TIME

        ret.mostViewedProfile = queryMostViewedProfile(opus, ALL_TIME)
        ret.totalVisitorCount = queryVisitorCount(opus, ALL_TIME)
        ret.totalDownloadCount = queryDownloadCount(opus, ALL_TIME)
        ret.monthlyVisitorCount = queryVisitorCount(opus, fromStr)
        ret.monthlyDownloadCount = queryDownloadCount(opus, fromStr)

        return ret
    }

    /**
     * Perform a Google Analytics query for the sessions and pageviews received by an
     * eFlora profile.
     * @param profileUri the uri of the profile to query; the webapp context should be
     * included if it is visible in Analytics
     * @param startDate the date on which to start the query (endDate is always "today")
     * @return the data, with caveats as noted in {@link #extractData(GaData)}
     */
    private Map queryForViews(String profileUri, String startDate) {
        def metrics = "ga:sessions,ga:pageviews"
        def filters = "ga:pagePath==${profileUri}"

        GaData result = analytics.data().ga()
                .get(viewIds, startDate, "today", metrics)
                .setFilters(filters)
                .execute()
        log.debug("Queried ${result.getSelfLink()}")
        log.trace(result)

        return extractData(result)
    }

    /**
     * Perform a Google Analytics query for the most viewed profile.
     * @return the data, with caveats as noted in {@link #extractData(GaData)}
     */
    private Map queryMostViewedProfile(Opus opus, String from) {
        def metrics = "ga:sessions,ga:pageviews"
        def dimensions = "ga:pagePath"
        def sort = "-ga:pageviews"
        def filters = "ga:pagePath=~^/opus/${opus.shortName?:opus.uuid}/profile/.*"

        GaData result = analytics.data().ga()
                .get(viewIds, from, "today", metrics)
                .setDimensions(dimensions)
                .setSort(sort)
                .setFilters(filters)
                .setMaxResults(1)
                .execute()
        log.debug("Queried ${result.getSelfLink()}")
        log.trace(result)

        return extractData(result)
    }

    /**
     * Perform a Google Analytics query for the number of unique visitors to the opus.
     */
    private Map queryVisitorCount(Opus opus, String from) {
        String metrics = "ga:visitors"
        String dimensions = "ga:pagePath"
        String filters = "ga:pagePath=~^/opus/${opus.shortName?:opus.uuid}/.*"

        GaData result = analytics.data().ga()
                .get(viewIds, from, "today", metrics)
                .setDimensions(dimensions)
                .setFilters(filters)
                .setMaxResults(1)
                .execute()
        log.debug("Queried ${result.getSelfLink()}")
        log.trace(result)

        return extractData(result)
    }

    /**
     * Perform a Google Analytics query for the number of hits to PDF download urls (ad-hoc PDFs or publications).
     * @return the data, with caveats as noted in {@link #extractData(GaData)}
     */
    private Map queryDownloadCount(Opus opus, String from) {
        def metrics = "ga:sessions,ga:pageviews"
        def dimensions = "ga:pagePath"
        def sort = "-ga:pageviews"
        def filters = "ga:pagePath=~^/opus/${opus.shortName?:opus.uuid}/profile/.*/(publication|pdf)/.*"

        GaData result = analytics.data().ga()
                .get(viewIds, from, "today", metrics)
                .setDimensions(dimensions)
                .setSort(sort)
                .setFilters(filters)
                .setMaxResults(1)
                .execute()
        log.debug("Queried ${result.getSelfLink()}")
        log.trace(result)

        return extractData(result)
    }

    /**
     * Extracts metric and dimension values from the result of a Google Analytics query.
     * <p>The data response, provided as a {@link GaData} object, is explained in the
     * <a href="https://developers.google.com/analytics/devguides/reporting/core/v3/reference#data_response">Analytics API reference</a>.
     *
     * @param result the query result
     * @return a map of the results containing the name of the metric or dimension (minus
     * its "ga:" prefix) as entry key (e.g. "sessions", "pageviews", "pagePath"), and the
     * content of the metric or dimension as entry value; a map is returned with
     * {@code null}s or String "0" as entry value when no data was returned in the query,
     * {@code null} is used when the data type of the column was "STRING" (usually for
     * dimensions), and "0" is used in all other cases (usually for metrics).
     */
    private static Map extractData(GaData result) {
        Map ret = [:]
        String name

        result?.columnHeaders?.eachWithIndex { column, index ->
            name = column?.name
            name = name[name.indexOf(':') + 1..-1]
            // We are only interested in the first row; there must be a non-null value
            if (column?.dataType == "STRING") {
                ret."${name}" = result.rows ? result.rows.first().get(index) : null
            } else {
                ret."${name}" = result.rows ? result.rows.first().get(index) : "0"
            }
        }

        return ret
    }
}
