package au.org.ala.profile

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentSkipListSet
import java.util.concurrent.atomic.AtomicInteger

import static groovyx.gpars.GParsPool.*

import static org.apache.commons.lang3.StringEscapeUtils.unescapeHtml4

class ImportService extends BaseDataAccessService {

    static final int IMPORT_THREAD_POOL_SIZE = 10

    ProfileService profileService
    NameService nameService

    def cleanupText(str) {
        if (str) {
            str = unescapeHtml4(str)
            // preserve line breaks as new lines, remove all other html
            str = str.replaceAll(/<p\/?>|<br\/?>/, "\n").replaceAll(/<.+?>/, "").trim()
        }
        return str
    }

    Term getOrCreateTerm(String vocabId, String name) {
        Vocab vocab = Vocab.findByUuid(vocabId)
        Term term = Term.findByNameAndVocab(name, vocab)
        if (!term) {
            term = new Term(name: name, vocab: vocab)
            term.save(flush: true)
        }
        term
    }

    /**
     * Profile import is a two-pass operation:
     * 1.) Use multiple threads to loop through the provided data set to find all values that should be unique (e.g. attribute terms, contributors, etc)
     * 1.1) Using a single thread, store them in the database and return a map of references.
     * 2.) Use multiple threads to loop through the provided data set again to process each individual record, retrieving references to unique values from the maps from pass 1.
     *
     * Querying data from the database, and writing records if they don't exist, is the slowest part of the import process.
     * Executing these 'get or create' actions concurrently results in race conditions and duplicated data.
     * Synchronising the methods resolves this issue, but dramatically increases the execution time of the import.
     *
     * The two-pass approach ensures no duplicate records are created, and does not significantly impact performance.
     *
     * @param opusId
     * @param profilesJson
     * @return
     */
    Map<String, String> importProfiles(String opusId, profilesJson) {
        Opus opus = Opus.findByUuid(opusId);

        Map<String, String> results = [:] as ConcurrentHashMap

        AtomicInteger success = new AtomicInteger(0)
        AtomicInteger index = new AtomicInteger(0)
        int reportInterval = 0.05 * profilesJson.size() // log at 5% intervals

        Map uniqueValues = findAndStoreUniqueValues(opus, profilesJson)
        Map<String, Term> vocab = uniqueValues.vocab
        Map<String, Contributor> contributors = uniqueValues.contributors

        log.info "Importing profiles ..."
        withPool(IMPORT_THREAD_POOL_SIZE) {
            profilesJson.eachParallel {
                boolean nameMatched = true
                index.incrementAndGet()
                Profile profile = Profile.findByScientificNameAndOpus(it.scientificName, opus);
                if (profile) {
                    log.info("Profile already exists in this opus for scientific name ${it.scientificName}")
                    results << [(it.scientificName): "Already exists"]
                } else {
                    if (!it.scientificName) {
                        results << [("Row${index}"): "Failed to import row ${index}, does not have a scientific name"]
                    } else {
                        String guid = nameService.getGuidForName(it.scientificName)

                        profile = new Profile(scientificName: it.scientificName, nameAuthor: it.nameAuthor, opus: opus, guid: guid, attributes: [], links: [], bhlLinks: []);

                        if (profile.guid) {
                            profileService.populateTaxonHierarchy(profile)
                            profile.nslNameIdentifier = nameService.getNSLNameIdentifier(profile.guid)
                        } else {
                            nameMatched = false
                        }

                        it.links.each {
                            if (it) {
                                profile.links << createLink(it, contributors)
                            }
                        }

                        it.bhl.each {
                            if (it) {
                                profile.bhlLinks << createLink(it, contributors)
                            }
                        }

                        Set<String> contributorNames = []
                        it.attributes.each {
                            if (it.title && it.text) {
                                Term term = vocab.get(it.title.trim())

                                String text = it.stripHtml?.booleanValue() ? cleanupText(it.text) : it.text
                                if (text) {
                                    Attribute attribute = new Attribute(title: term, text: text)
                                    attribute.uuid = UUID.randomUUID().toString()

                                    if (it.creators) {
                                        attribute.creators = []
                                        it.creators.each {
                                            String name = cleanName(it)
                                            if (name) {
                                                Contributor contrib = contributors[name]
                                                if (contrib) {
                                                    attribute.creators << contrib
                                                    contributorNames << contrib.name
                                                } else {
                                                    log.warn("Missing contributor for name '${name}'")
                                                }
                                            }
                                        }
                                    }

                                    if (it.editors) {
                                        attribute.editors = []
                                        it.editors.each {
                                            String name = cleanName(it)
                                            if (name) {
                                                attribute.editors << contributors[name]
                                            }
                                        }
                                    }

                                    attribute.profile = profile
                                    profile.attributes << attribute
                                }
                            }
                        }

                        if (it.authorship) {
                            profile.authorship = it.authorship.collect {
                                new Authorship(category: it.category, text: it.text)
                            }
                        } else {
                            profile.authorship = [new Authorship(category: "Author", text: contributorNames.join(", "))]
                        }

                        profile.save(flush: true)

                        if (profile.errors.allErrors.size() > 0) {
                            log.error("Failed to save ${profile}")
                            profile.errors.each { log.error(it) }
                            results << [(it.scientificName): "Failed: ${profile.errors.allErrors.get(0)}"]
                        } else {
                            results << [(it.scientificName): nameMatched ? "Success" : "Success (Unmatched name)"]
                            success.incrementAndGet()
                            if (index % reportInterval == 0) {
                                log.debug("Saved ${success} of ${profilesJson.size()}")
                            }
                        }
                    }
                }
            }
        }
        log.debug "${success} of ${profilesJson.size()} records imported"

        results
    }

    Map findAndStoreUniqueValues(Opus opus, profilesJson) {
        Set<String> uniqueTerms = [] as ConcurrentSkipListSet
        Set<String> uniqueContributors = [] as ConcurrentSkipListSet

        log.info "Retrieving unique data..."
        withPool(IMPORT_THREAD_POOL_SIZE) {
            profilesJson.eachParallel {
                it.attributes.each { attr ->
                    if (attr.title) {
                        uniqueTerms << attr.title.trim()
                    }

                    attr.editors?.each { name ->
                        uniqueContributors << cleanName(name)
                    }
                    attr.creators?.each { name ->
                        uniqueContributors << cleanName(name)
                    }
                }
            }
        }

        log.info "Storing unique vocabulary (${uniqueTerms.size()} terms) ..."
        Map<String, Term> vocab = [:]
        uniqueTerms.each {
            vocab[it] = getOrCreateTerm(opus.attributeVocabUuid, it)
        }

        log.info "Storing unique contributors (${uniqueContributors.size()} names) ..."
        Map<String, Contributor> contributors = [:]
        uniqueContributors.each {
            contributors[it] = getOrCreateContributor(it, opus.dataResourceUid)
        }

        [vocab: vocab, contributors: contributors]
    }

    private static cleanName(String name) {
        name.replaceAll("\\(.*\\)", "").replaceAll(" +", " ").trim()
    }

    Contributor getOrCreateContributor(String name, String opusDataResourceId) {
        Contributor contributor = Contributor.findByName(name)
        if (!contributor) {
            contributor = new Contributor(name: name, uuid: UUID.randomUUID().toString(), dataResourceUid: opusDataResourceId)
        }
        contributor
    }

    Link createLink(data, contributors) {
        Link link = new Link(data)
        link.uuid = UUID.randomUUID().toString()

        if (data.creators) {
            link.creators = []
            data.creators.each {
                String name = cleanName(it)
                if (name) {
                    link.creators << contributors[name]
                }
            }
        }

        link
    }
}
