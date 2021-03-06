package au.org.ala.profile

import au.org.ala.web.AuthService
import org.springframework.web.multipart.MultipartFile

class ProfileServiceSpec extends BaseIntegrationSpec {

    ProfileService service = new ProfileService()

    def setup() {
        service.nameService = Mock(NameService)
        service.nameService.matchName(_) >> [scientificName: "sciName", author: "fred", guid: "ABC"]
        service.authService = Mock(AuthService)
        service.authService.getUserId() >> "fred"
        service.authService.getUserForUserId(_) >> [displayName: "Fred Bloggs"]
        service.bieService = Mock(BieService)
        service.bieService.getClassification(_) >> null
        service.doiService = Mock(DoiService)
        service.doiService.mintDOI(_, _) >> [status: "success", doi: "1234"]
        service.grailsApplication = [config: [snapshot:[directory: "bla"]]]
        service.vocabService = Mock(VocabService)
        service.vocabService.getOrCreateTerm(_, _) >> {name, id -> [name: name, vocabId: id]}
    }

    def "createProfile should expect both arguments to be provided"() {
        when:
        service.createProfile(null, [a: "b"])

        then:
        thrown(IllegalArgumentException)

        when:
        service.createProfile("bla", [:])

        then:
        thrown(IllegalArgumentException)
    }

    def "createProfile should fail if the specified opus does not exist"() {
        when:
        service.createProfile("unknown", [a: "bla"])

        then:
        thrown(IllegalStateException)
    }

    def "createProfile should return the new profile on success"() {
        given:
        Opus opus = new Opus(glossary: new Glossary(), dataResourceUid: "dr1234", title: "title")
        save opus

        when:
        Profile profile = service.createProfile(opus.uuid, [scientificName: "sciName"])

        then:
        profile != null && profile.id != null
        Profile.count() == 1
        Profile.list()[0].scientificName == "sciName"
    }

    def "createProfile should default the 'author' authorship to the current user"() {
        given:
        Opus opus = new Opus(glossary: new Glossary(), dataResourceUid: "dr1234", title: "title")
        save opus

        when:
        Profile profile = service.createProfile(opus.uuid, [scientificName: "sciName"])

        then:
        profile.authorship.size() == 1
        profile.authorship[0].category.name == "Author"
        profile.authorship[0].text == "Fred Bloggs"
    }

    def "delete profile should remove the record"() {
        given:
        Opus opus = new Opus(glossary: new Glossary(), dataResourceUid: "dr1234", title: "title")
        save opus
        Profile profile = new Profile(opus: opus, scientificName: "name")
        save profile
        String profileId = profile.uuid

        expect:
        Profile.count() == 1

        when:
        service.deleteProfile(profileId)

        then:
        Profile.count() == 0
    }

    def "delete should throw IllegalArgumentException if no profile id is provided"() {
        when:
        service.deleteProfile(null)

        then:
        thrown(IllegalArgumentException)
    }

    def "updateProfile should throw IllegalArgumentException if no profile id or data are provided"() {
        when:
        service.updateProfile("something", null)

        then:
        thrown(IllegalArgumentException)

        when:
        service.updateProfile(null, [a: "bla"])

        then:
        thrown(IllegalArgumentException)
    }

    def "updateProfile should throw IllegalStateException if the profile does not exist"() {
        when:
        service.updateProfile("bla", [a: "bla"])

        then:
        thrown(IllegalStateException)
    }

    def "updateProfile should update all data provided"() {
        given:
        Opus opus = new Opus(glossary: new Glossary(), dataResourceUid: "dr1234", title: "title")
        save opus
        Profile profile = new Profile(opus: opus, scientificName: "sciName", primaryImage: "12345")
        save profile

        when: "incoming data contains a variety of fields"
        Map data = [primaryImage: "09876",
                    excludedImages: ["4", "5", "6"],
                    specimenIds: ["4", "5", "6"],
                    bhlLinks: [[url: "three"], [url: "four"]],
                    links: [[url: "five"], [url: "six"]],
                    bibliography: [[text: "bib1"], [text: "bib2"]]]
        service.updateProfile(profile.uuid, data)

        then: "all appropriate fields are updated"
        profile.primaryImage == "09876"
        profile.excludedImages == ["4", "5", "6"]
        profile.specimenIds == ["4", "5", "6"]
        profile.bhlLinks.every {it.url == "three" || it.url == "four"}
        profile.links.every {it.url == "five" || it.url == "six"}
        profile.bibliography.each { it.text == "bib1" || it.text == "bib2" }
    }

    def "saveImages should change the primary image only if the new data contains the element"() {
        given:
        Opus opus = new Opus(glossary: new Glossary(), dataResourceUid: "dr1234", title: "title")
        save opus
        Profile profile = new Profile(opus: opus, scientificName: "sciName", primaryImage: "12345")
        save profile

        when: "the incoming data does not have a primaryImage attribute"
        service.saveImages(profile, [a: "bla"])

        then: "there should be no change"
        profile.primaryImage == "12345"
    }

    def "saveImages should change the primary image only if the new data contains the element and the value is different"() {
        given:
        Opus opus = new Opus(glossary: new Glossary(), dataResourceUid: "dr1234", title: "title")
        save opus
        Profile profile

        when: "the incoming primary image is null"
        profile = new Profile(opus: opus, scientificName: "sciName", primaryImage: "12345")
        save profile
        service.saveImages(profile, [primaryImage: null])

        then: "there should be no change"
        profile.primaryImage == "12345"

        when: "the incoming primary image is different"
        profile = new Profile(opus: opus, scientificName: "sciName", primaryImage: "12345")
        save profile
        service.saveImages(profile, [primaryImage: "09876"])

        then: "the profile should be updated"
        profile.primaryImage == "09876"

        when: "the incoming data does not have a primaryImage attribute"
        profile = new Profile(opus: opus, scientificName: "sciName", primaryImage: "12345")
        save profile
        service.saveImages(profile, [a: "bla"])

        then: "there should be no change"
        profile.primaryImage == "12345"
    }

    def "saveImages should change the excluded images only if the incoming data has the excludedImages attribute and the value is different"() {
        given:
        Opus opus = new Opus(glossary: new Glossary(), dataResourceUid: "dr1234", title: "title")
        save opus
        Profile profile

        when: "the incoming value is different"
        profile = new Profile(opus: opus, scientificName: "sciName", excludedImages: ["1", "2", "3"])
        save profile
        service.saveImages(profile, [excludedImages: ["4", "5", "6"]])

        then: "the profile should be replaced"
        profile.excludedImages == ["4", "5", "6"]

        when: "the incoming data does not have the attribute"
        profile = new Profile(opus: opus, scientificName: "sciName", excludedImages: ["1", "2", "3"])
        save profile
        service.saveImages(profile, [a: "bla"])

        then: "there should be no change"
        profile.excludedImages == ["1", "2", "3"]

        when: "the incoming attribute is empty"
        profile = new Profile(opus: opus, scientificName: "sciName", excludedImages: ["1", "2", "3"])
        save profile
        service.saveImages(profile, [excludedImages: []])

        then: "all existing excluded images should be removed"
        profile.excludedImages == []

        when: "the incoming attribute is null"
        profile = new Profile(opus: opus, scientificName: "sciName", excludedImages: ["1", "2", "3"])
        save profile
        service.saveImages(profile, [excludedImages: null])

        then: "all existing excluded images should be removed"
        profile.excludedImages == []
    }

    def "saveSpecimens should change the specimens only if the incoming data has the specimenIds attribute and the value is different"() {
        given:
        Opus opus = new Opus(glossary: new Glossary(), dataResourceUid: "dr1234", title: "title")
        save opus
        Profile profile

        when: "the incoming data does not have the attribute"
        profile = new Profile(opus: opus, scientificName: "sciName", specimenIds: ["1", "2", "3"])
        save profile
        service.saveSpecimens(profile, [a: "bla"])

        then: "there should be no change"
        profile.specimenIds == ["1", "2", "3"]

        when: "the incoming attribute is empty"
        profile = new Profile(opus: opus, scientificName: "sciName", specimenIds: ["1", "2", "3"])
        save profile
        service.saveSpecimens(profile, [specimenIds: []])

        then: "all existing specimens should be removed"
        profile.specimenIds == []

        when: "the incoming value is different"
        profile = new Profile(opus: opus, scientificName: "sciName", specimenIds: ["1", "2", "3"])
        save profile
        service.saveSpecimens(profile, [specimenIds: ["4", "5", "6"]])

        then: "the profile should be replaced"
        profile.specimenIds == ["4", "5", "6"]

        when: "the incoming attribute is null"
        profile = new Profile(opus: opus, scientificName: "sciName", specimenIds: ["1", "2", "3"])
        save profile
        service.saveSpecimens(profile, [specimenIds: null])

        then: "all existing ss should be removed"
        profile.specimenIds == []

    }

    def "saveSpecimens should save changes to the profile draft if one exists"() {
        given:
        Opus opus = new Opus(glossary: new Glossary(), dataResourceUid: "dr1234", title: "title")
        save opus
        Profile profile

        when:
        profile = new Profile(opus: opus, scientificName: "sciName", specimenIds: ["1", "2", "3"], draft: [uuid: "asd", scientificName: "sciName", specimenIds: ["1", "2", "3"]])
        save profile
        service.saveSpecimens(profile, [specimenIds: ["4", "5", "6"]])

        then: "there should be no change"
        profile.specimenIds == ["1", "2", "3"]
        profile.draft.specimenIds == ["4", "5", "6"]
    }

    def "saveBibliography should not change the bibliography if the incoming data does not have the bibliography attribute"() {
        given:
        Opus opus = new Opus(glossary: new Glossary(), dataResourceUid: "dr1234", title: "title")
        save opus
        Bibliography bib1 = new Bibliography(uuid: "1", text: "one")
        Bibliography bib2 = new Bibliography(uuid: "2", text: "two")
        Profile profile = new Profile(opus: opus, scientificName: "sciName", bibliography: [bib1, bib2])
        save profile

        when: "the incoming data does not have the attribute"
        service.saveBibliography(profile, [a: "bla"])

        then: "there should be no change"
        profile.bibliography.contains(bib1) && profile.bibliography.contains(bib2)
    }

    def "saveBibliography should not change the bibliography if the incoming data is the same"() {
        given:
        Opus opus = new Opus(glossary: new Glossary(), dataResourceUid: "dr1234", title: "title")
        save opus
        Bibliography bib1 = new Bibliography(uuid: "1", text: "one")
        Bibliography bib2 = new Bibliography(uuid: "2", text: "two")
        Profile profile = new Profile(opus: opus, scientificName: "sciName", bibliography: [bib1, bib2])
        save profile

        when: "the incoming data the same"
        service.saveBibliography(profile, [bibliography: [[uuid: "1", text: "one", order: 1], [uuid: "2", text: "two", order: 2]]])

        then: "there should be no change"
        profile.bibliography.each { it.id == bib1.id || it.id == bib2.id }
    }

    def "saveBibliography should change the bibliography the incoming data contains existing and new records"() {
        given:
        Opus opus = new Opus(glossary: new Glossary(), dataResourceUid: "dr1234", title: "title")
        save opus
        Bibliography bib1 = new Bibliography(uuid: "1", text: "one")
        Bibliography bib2 = new Bibliography(uuid: "2", text: "two")
        Profile profile = new Profile(opus: opus, scientificName: "sciName", bibliography: [bib1, bib2])
        save profile

        when: "the incoming data contains existing and new records"
        service.saveBibliography(profile, [bibliography: [[text: "four"], [uuid: "1", text: "one"]]])

        then: "the profile's list should be updated "
        profile.bibliography.each { it.id == bib1.id || it.text == "four" }
    }

    def "saveBibliography should change the bibliography the incoming data contains different records"() {
        given:
        Opus opus = new Opus(glossary: new Glossary(), dataResourceUid: "dr1234", title: "title")
        save opus
        Bibliography bib1 = new Bibliography(uuid: "1", text: "one")
        Bibliography bib2 = new Bibliography(uuid: "2", text: "two")
        Profile profile = new Profile(opus: opus, scientificName: "sciName", bibliography: [bib1, bib2])
        save profile

        when: "the incoming value is different"
        service.saveBibliography(profile, [bibliography: [[text: "four"]]])

        then: "the profile should be replaced"
        profile.bibliography.size() == 1
        profile.bibliography[0].text == "four"
    }

    def "saveBibliography should not change the bibliography if the incoming data contains the same records"() {
        given:
        Opus opus = new Opus(glossary: new Glossary(), dataResourceUid: "dr1234", title: "title")
        save opus
        Bibliography bib1 = new Bibliography(uuid: "1", text: "one")
        Bibliography bib2 = new Bibliography(uuid: "2", text: "two")
        Profile profile = new Profile(opus: opus, scientificName: "sciName", bibliography: [bib1, bib2])
        save profile

        when: "the incoming data the same"
        service.saveBibliography(profile, [bibliography: [[uuid: "1", text: "one", order: 1], [uuid: "2", text: "two", order: 2]]])

        then: "there should be no change"
        profile.bibliography.every { it.id == bib1.id || it.id == bib2.id }
    }

    def "saveBibliography should clear the bibliography if the incoming data is empty"() {
        given:
        Opus opus = new Opus(glossary: new Glossary(), dataResourceUid: "dr1234", title: "title")
        save opus
        Bibliography bib1 = new Bibliography(uuid: "1", text: "one")
        Bibliography bib2 = new Bibliography(uuid: "2", text: "two")
        Profile profile = new Profile(opus: opus, scientificName: "sciName", bibliography: [bib1, bib2])
        save profile

        when: "the incoming attribute is empty"
        service.saveBibliography(profile, [bibliography: []])

        then: "all existing bibliography should be removed"
        profile.bibliography.isEmpty()
    }

    def "saveBibliography should clear the bibliography if the incoming data is null"() {
        given:
        Opus opus = new Opus(glossary: new Glossary(), dataResourceUid: "dr1234", title: "title")
        save opus
        Bibliography bib1 = new Bibliography(uuid: "1", text: "one")
        Bibliography bib2 = new Bibliography(uuid: "2", text: "two")
        Profile profile = new Profile(opus: opus, scientificName: "sciName", bibliography: [bib1, bib2])
        save profile

        when: "the incoming attribute is empty"
        service.saveBibliography(profile, [bibliography: null])

        then: "all existing bibliography should be removed"
        profile.bibliography.isEmpty()
    }

    def "saveBibliography should update the profile draft if one exists"() {
        given:
        Opus opus = new Opus(glossary: new Glossary(), dataResourceUid: "dr1234", title: "title")
        save opus
        Bibliography bib1 = new Bibliography(uuid: "1", text: "one")
        Bibliography bib2 = new Bibliography(uuid: "2", text: "two")
        Profile profile = new Profile(opus: opus, scientificName: "sciName", bibliography: [bib1, bib2], draft: [uuid: "123", scientificName: "asd", bibliography: [bib1, bib2]])
        save profile

        when: "the incoming data the same"
        service.saveBibliography(profile, [bibliography: [[uuid: "3", text: "three", order: 1], [uuid: "4", text: "four", order: 2]]])

        then: "there should be no change"
        profile.bibliography.every { it.id == bib1.id || it.id == bib2.id }
        profile.draft.bibliography.every { it.text == "three" || it.text == "four" }
    }

    def "saveBHLLinks should throw an IllegalArgumentException if the profile id or data are not provided"() {
        when:
        service.saveBHLLinks(null, [a:"b"])

        then:
        thrown IllegalArgumentException

        when:
        service.saveBHLLinks("id", [:])

        then:
        thrown IllegalArgumentException
    }

    def "saveBHLLinks should throw an IllegalStateException if the profile does not exist"() {
        when:
        service.saveBHLLinks("unknown", [a:"b"])

        then:
        thrown IllegalStateException
    }

    def "saveBHLLinks should remove all links if the incoming list is empty"() {
        given:
        Opus opus = new Opus(glossary: new Glossary(), dataResourceUid: "dr1234", title: "title")
        save opus
        Link link1 = new Link(uuid: "1", url: "one", description: "desc", title: "title")
        Link link2 = new Link(uuid: "2", url: "two", description: "desc", title: "title")
        Profile profile = new Profile(opus: opus, scientificName: "sciName", bhlLinks: [link1, link2])
        save profile

        when:
        service.saveBHLLinks(profile.uuid, [links: []])

        then:
        profile.bhlLinks.isEmpty()
        Link.count() == 0
    }

    def "saveBHLLinks should replace all links if the incoming list contains all new elements"() {
        given:
        Opus opus = new Opus(glossary: new Glossary(), dataResourceUid: "dr1234", title: "title")
        save opus
        Link link1 = new Link(uuid: "1", url: "one", description: "desc", title: "title")
        Link link2 = new Link(uuid: "2", url: "two", description: "desc", title: "title")
        Profile profile = new Profile(opus: opus, scientificName: "sciName", bhlLinks: [link1, link2])
        save profile

        when:
        service.saveBHLLinks(profile.uuid, [links: [[url: "three"], [url: "four"]]])

        then:
        profile.bhlLinks.every {it.url == "three" || it.url == "four"}
    }

    def "saveBHLLinks should combine all links if the incoming list contains new and existing elements"() {
        given:
        Opus opus = new Opus(glossary: new Glossary(), dataResourceUid: "dr1234", title: "title")
        save opus
        Link link1 = new Link(uuid: "1", url: "one", description: "desc", title: "title")
        Link link2 = new Link(uuid: "2", url: "two", description: "desc", title: "title")
        Profile profile = new Profile(opus: opus, scientificName: "sciName", bhlLinks: [link1, link2])
        save profile

        when:
        service.saveBHLLinks(profile.uuid, [links: [[url: "one", uuid: "1"], [url: "four"]]])

        then:
        profile.bhlLinks.every {it.url == "one" || it.url == "four"}
    }

    def "saveBHLLinks should update the profile draft if one exists"() {
        given:
        Opus opus = new Opus(glossary: new Glossary(), dataResourceUid: "dr1234", title: "title")
        save opus
        Link link1 = new Link(uuid: "1", url: "one", description: "desc", title: "title")
        Link link2 = new Link(uuid: "2", url: "two", description: "desc", title: "title")
        Profile profile = new Profile(opus: opus, scientificName: "sciName", bhlLinks: [link1, link2], draft: [uuid: "123",scientificName: "sciName", bhlLinks: [link1, link2]])
        save profile

        when:
        service.saveBHLLinks(profile.uuid, [links: [[url: "three"], [url: "four"]]])

        then:
        profile.bhlLinks.every {it.url == "one" || it.url == "two"}
        profile.draft.bhlLinks.every {it.url == "three" || it.url == "four"}
    }

    def "saveLinks should throw an IllegalArgumentException if the profile id or data are not provided"() {
        when:
        service.saveLinks(null, [a:"b"])

        then:
        thrown IllegalArgumentException

        when:
        service.saveLinks("id", [:])

        then:
        thrown IllegalArgumentException
    }

    def "saveLinks should throw an IllegalStateException if the profile does not exist"() {
        when:
        service.saveLinks("unknown", [a:"b"])

        then:
        thrown IllegalStateException
    }

    def "saveLinks should remove all links if the incoming list is empty"() {
        given:
        Opus opus = new Opus(glossary: new Glossary(), dataResourceUid: "dr1234", title: "title")
        save opus
        Link link1 = new Link(uuid: "1", url: "one", description: "desc", title: "title")
        Link link2 = new Link(uuid: "2", url: "two", description: "desc", title: "title")
        Profile profile = new Profile(opus: opus, scientificName: "sciName", links: [link1, link2])
        save profile

        when:
        service.saveLinks(profile.uuid, [links: []])

        then:
        profile.links.isEmpty()
        Link.count() == 0
    }

    def "saveLinks should replace all links if the incoming list contains all new elements"() {
        given:
        Opus opus = new Opus(glossary: new Glossary(), dataResourceUid: "dr1234", title: "title")
        save opus
        Link link1 = new Link(uuid: "1", url: "one", description: "desc", title: "title")
        Link link2 = new Link(uuid: "2", url: "two", description: "desc", title: "title")
        Profile profile = new Profile(opus: opus, scientificName: "sciName", links: [link1, link2])
        save profile

        when:
        service.saveLinks(profile.uuid, [links: [[url: "three"], [url: "four"]]])

        then:
        profile.links.every {it.url == "three" || it.url == "four"}
    }

    def "saveLinks should combine all links if the incoming list contains new and existing elements"() {
        given:
        Opus opus = new Opus(glossary: new Glossary(), dataResourceUid: "dr1234", title: "title")
        save opus
        Link link1 = new Link(uuid: "1", url: "one", description: "desc", title: "title")
        Link link2 = new Link(uuid: "2", url: "two", description: "desc", title: "title")
        Profile profile = new Profile(opus: opus, scientificName: "sciName", links: [link1, link2])
        save profile

        when:
        service.saveLinks(profile.uuid, [links: [[url: "one", uuid: "1"], [url: "four"]]])

        then:
        profile.links.every {it.url == "one" || it.url == "four"}
    }

    def "saveLinks should update the profile draft if one exists"() {
        given:
        Opus opus = new Opus(glossary: new Glossary(), dataResourceUid: "dr1234", title: "title")
        save opus
        Link link1 = new Link(uuid: "1", url: "one", description: "desc", title: "title")
        Link link2 = new Link(uuid: "2", url: "two", description: "desc", title: "title")
        Profile profile = new Profile(opus: opus, scientificName: "sciName", links: [link1, link2], draft: [uuid: "123", scientificName: "sciName", links: [link1, link2]])
        save profile

        when:
        service.saveLinks(profile.uuid, [links: [[url: "three"], [url: "four"]]])

        then:
        profile.links.every {it.url == "one" || it.url == "two"}
        profile.draft.links.every {it.url == "three" || it.url == "four"}
    }

    def "savePublication should throw an IllegalArgumentException if no profile id or file are provided"() {
        when:
        service.savePublication(null, Mock(MultipartFile))

        then:
        thrown IllegalArgumentException

        when:
        service.savePublication("a", null)

        then:
        thrown IllegalArgumentException
    }

    def "savePublication should throw IllegalStateException if the profile does not exist"() {
        when:
        service.savePublication("unknown", Mock(MultipartFile))

        then:
        thrown IllegalStateException
    }

    def "savePublication should create a new Publication record"() {
        given:
        Opus opus = new Opus(glossary: new Glossary(), dataResourceUid: "dr1234", title: "title")
        save opus
        Profile profile = new Profile(opus: opus, scientificName: "sciName")
        save profile
        MultipartFile mockFile = Mock(MultipartFile)

        when:
        service.savePublication(profile.uuid, mockFile)

        then:
        Profile.list().get(0).publications.size() == 1
        1 * mockFile.transferTo(_)
    }

    def "savePublication should assign a DOI to the new publication if the DoiService returns successfully"() {
        given:
        Opus opus = new Opus(glossary: new Glossary(), dataResourceUid: "dr1234", title: "title")
        save opus
        Profile profile = new Profile(opus: opus, scientificName: "sciName")
        save profile
        MultipartFile mockFile = Mock(MultipartFile)
        service.doiService = Mock(DoiService)
        service.doiService.mintDOI(_, _) >> [status: "success", doi: "12345/0987"]

        when:
        Publication pub = service.savePublication(profile.uuid, mockFile)

        then:
        Profile.list().get(0).publications.size() == 1
        pub.doi == "12345/0987"
    }

    def "savePublication should return an error if the DoiService fails"() {
        given:
        Opus opus = new Opus(glossary: new Glossary(), dataResourceUid: "dr1234", title: "title")
        save opus
        Profile profile = new Profile(opus: opus, scientificName: "sciName")
        save profile
        MultipartFile mockFile = Mock(MultipartFile)
        service.doiService = Mock(DoiService)
        service.doiService.mintDOI(_, _) >> [status: "error", statusCode: "E001", message: "Something blew up!!"]

        when:
        def result = service.savePublication(profile.uuid, mockFile)

        then:
        Profile.list().get(0).publications.size() == 0
        result == [status: "error", statusCode: "E001", message: "Something blew up!!"]
    }

    def "deleteAttribute should throw IllegalStateException if no attributeId or profileId are provided"() {
        when:
        service.deleteAttribute(null, "profileId")

        then:
        thrown IllegalArgumentException

        when:
        service.deleteAttribute("attrId", null)

        then:
        thrown IllegalArgumentException
    }

    def "deleteAttribute should throw IllegalStateException if the attribute does not exist"() {
        given:
        Opus opus = new Opus(glossary: new Glossary(), dataResourceUid: "dr1234", title: "title")
        save opus
        Profile profile = new Profile(opus: opus, scientificName: "sciName")
        save profile

        when:
        service.deleteAttribute("unknown", profile.uuid)

        then:
        thrown IllegalStateException
    }

    def "deleteAttribute should throw IllegalStateException if the profile does not exist"() {
        given:
        Opus opus = new Opus(glossary: new Glossary(), dataResourceUid: "dr1234", title: "title")
        save opus
        Profile profile = new Profile(opus: opus, scientificName: "sciName")
        save profile
        Vocab vocab = new Vocab(name: "vocab1")
        Term term = new Term(uuid: "1", name: "title")
        vocab.addToTerms(term)
        save vocab
        Attribute attribute = new Attribute(uuid: "1", title: term, text: "text")
        profile.addToAttributes(attribute)
        save profile

        when:
        service.deleteAttribute(attribute.uuid, "unknown")

        then:
        thrown IllegalStateException
    }

    def "deleteAttribute should remove the attribute record"() {
        given:
        Opus opus = new Opus(glossary: new Glossary(), dataResourceUid: "dr1234", title: "title")
        save opus
        Profile profile = new Profile(opus: opus, scientificName: "sciName")
        save profile
        Vocab vocab = new Vocab(name: "vocab1")
        Term term = new Term(uuid: "1", name: "title")
        vocab.addToTerms(term)
        save vocab
        Attribute attribute = new Attribute(uuid: "1", title: term, text: "text", profile: profile, contributors: [])
        profile.addToAttributes(attribute)
        save attribute
        save profile

        expect:
        Attribute.count() == 1
        profile.attributes.size() == 1

        when:
        service.deleteAttribute(attribute.uuid, profile.uuid)

        then:
        Attribute.count() == 0
    }

    def "deleteAttribute should update the profile draft if one exists"() {
        given:
        Opus opus = new Opus(glossary: new Glossary(), dataResourceUid: "dr1234", title: "title")
        save opus
        Profile profile = new Profile(opus: opus, scientificName: "sciName", draft: [uuid: "123", scientificName: "sciName"])
        save profile
        Vocab vocab = new Vocab(name: "vocab1")
        Term term = new Term(uuid: "1", name: "title")
        vocab.addToTerms(term)
        save vocab
        Attribute attribute = new Attribute(uuid: "1", title: term, text: "text", profile: profile)
        profile.addToAttributes(attribute)
        profile.draft.attributes = [new Attribute(uuid: "1", title: term, text: "text")]
        save attribute
        save profile

        expect:
        Attribute.count() == 1
        profile.attributes.size() == 1

        when:
        service.deleteAttribute(attribute.uuid, profile.uuid)

        then:
        Attribute.count() == 1
        profile.attributes.size() == 1
        profile.draft.attributes.size() == 0
    }

    def "updateAttribute should throw IllegalArgumentException if no attribute id, profile id or data are provided"() {
        when:
        service.updateAttribute(null, "p", [a: "b"])

        then:
        thrown IllegalArgumentException

        when:
        service.updateAttribute("a", null, [a: "b"])

        then:
        thrown IllegalArgumentException

        when:
        service.updateAttribute("a", "p", [:])

        then:
        thrown IllegalArgumentException
    }

    def "updateAttribute should throw IllegalStateException if the attribute does not exist"() {
        given:
        Opus opus = new Opus(glossary: new Glossary(), dataResourceUid: "dr1234", title: "title")
        save opus
        Profile profile = new Profile(opus: opus, scientificName: "sciName")
        save profile
        Vocab vocab = new Vocab(name: "vocab1")
        Term term = new Term(uuid: "1", name: "title")
        vocab.addToTerms(term)
        save vocab
        Attribute attribute = new Attribute(uuid: "1", title: term, text: "text", profile: profile, contributors: [])
        profile.addToAttributes(attribute)
        save attribute
        save profile

        when:
        service.updateAttribute("unknown", profile.uuid, [a: "b"])

        then:
        thrown IllegalStateException
    }

    def "updateAttribute should throw IllegalStateException if the profile does not exist"() {
        given:
        Vocab vocab = new Vocab(name: "vocab1")
        Term term = new Term(uuid: "1", name: "title")
        vocab.addToTerms(term)
        save vocab
        Opus opus = new Opus(glossary: new Glossary(), dataResourceUid: "dr1234", title: "title")
        save opus
        Profile profile = new Profile(opus: opus, scientificName: "sciName")
        save profile
        Attribute attribute = new Attribute(uuid: "1", title: term, text: "text", profile: profile, contributors: [])
        profile.addToAttributes(attribute)
        save attribute
        save profile

        when:
        service.updateAttribute(attribute.uuid, "unknown", [a: "b"])

        then:
        thrown IllegalStateException
    }

    def "updateAttribute should update the attribute fields"() {
        given:
        Vocab vocab = new Vocab(name: "vocab1")
        Term term1 = new Term(uuid: "1", name: "title1")
        Term term2 = new Term(uuid: "2", name: "title2")
        vocab.addToTerms(term1)
        vocab.addToTerms(term2)
        save vocab
        Opus opus = new Opus(glossary: new Glossary(), dataResourceUid: "dr1234", title: "title", attributeVocabUuid: vocab.uuid)
        save opus
        Profile profile = new Profile(opus: opus, scientificName: "sciName")
        save profile
        Attribute attribute = new Attribute(uuid: "1", title: term1, text: "text", profile: profile, contributors: [])
        profile.addToAttributes(attribute)
        save attribute
        save profile
        Contributor contributor = new Contributor(userId: "123", name: "fred")
        save contributor

        service.vocabService = new VocabService()

        when:
        service.updateAttribute(attribute.uuid, profile.uuid, [title: "title2", text: "updatedText", userId: "123", significantEdit: true])

        then:
        Attribute a = Attribute.list()[0]
        a.title == term2
        a.text == "updatedText"
        a.editors == [contributor] as Set
    }

    def "updateAttribute should update profile draft if one exists"() {
        given:
        Vocab vocab = new Vocab(name: "vocab1")
        Term term1 = new Term(uuid: "1", name: "title1")
        Term term2 = new Term(uuid: "2", name: "title2")
        vocab.addToTerms(term1)
        vocab.addToTerms(term2)
        save vocab
        Opus opus = new Opus(glossary: new Glossary(), dataResourceUid: "dr1234", title: "title", attributeVocabUuid: vocab.uuid)
        save opus
        Profile profile = new Profile(opus: opus, scientificName: "sciName", draft: [uuid: "123", scientificName: "sciName"])
        save profile
        Attribute attribute = new Attribute(uuid: "1", title: term1, text: "text", profile: profile)
        profile.addToAttributes(attribute)
        profile.draft.attributes = [new Attribute(uuid: "1", title: term1, text: "text")]
        save attribute
        save profile

        service.vocabService = new VocabService()

        when:
        service.updateAttribute(attribute.uuid, profile.uuid, [text: "updatedText"])

        then:
        Attribute.list()[0].text == "text"
        profile.draft.attributes[0].text == "updatedText"
    }

    def "createAttribute should throw IllegalArgumentException if no profile id or data are provided"() {
        when:
        service.createAttribute(null, [a: "b"])

        then:
        thrown IllegalArgumentException

        when:
        service.createAttribute("p", [:])

        then:
        thrown IllegalArgumentException
    }


    def "createAttribute should throw IllegalStateException if the profile does not exist"() {
        given:
        Vocab vocab = new Vocab(name: "vocab1")
        Term term = new Term(uuid: "1", name: "title")
        vocab.addToTerms(term)
        save vocab
        Opus opus = new Opus(glossary: new Glossary(), dataResourceUid: "dr1234", title: "title")
        save opus
        Profile profile = new Profile(opus: opus, scientificName: "sciName")
        save profile

        when:
        service.createAttribute("unknown", [a: "b"])

        then:
        thrown IllegalStateException
    }

    def "createAttribute should update the attribute fields"() {
        given:
        Vocab vocab = new Vocab(name: "vocab1")
        Term term = new Term(uuid: "1", name: "title")
        vocab.addToTerms(term)
        save vocab
        Opus opus = new Opus(glossary: new Glossary(), dataResourceUid: "dr1234", title: "title", attributeVocabUuid: vocab.uuid)
        save opus
        Profile profile = new Profile(opus: opus, scientificName: "sciName")
        save profile
        Attribute attribute = new Attribute(uuid: "1", title: term, text: "text", profile: profile, contributors: [])
        profile.addToAttributes(attribute)
        save attribute
        save profile
        Contributor fred = new Contributor(userId: "123", name: "fred")
        Contributor bob = new Contributor(userId: "987", name: "bob")
        save fred
        save bob

        service.vocabService = new VocabService()

        when:
        service.createAttribute(profile.uuid, [title: "title", text: "updatedText", userId: "123", editors: ["bob"]])

        then:
        Attribute.count() == 2
        Attribute a = Attribute.list()[1]
        a.title == term
        a.creators.size() == 1
        a.creators[0].uuid == fred.uuid
        a.editors.size() == 1
        a.editors[0].uuid == bob.uuid
        a.text == "updatedText"
        a.original == null
    }

    def "createAttribute should update the profile draft if one exists"() {
        given:
        Vocab vocab = new Vocab(name: "vocab1")
        Term term = new Term(uuid: "1", name: "title")
        vocab.addToTerms(term)
        save vocab
        Opus opus = new Opus(glossary: new Glossary(), dataResourceUid: "dr1234", title: "title", attributeVocabUuid: vocab.uuid)
        save opus
        Profile profile = new Profile(opus: opus, scientificName: "sciName", draft: [uuid: "123", scientificName: "sciName"])
        save profile

        service.vocabService = new VocabService()

        when:
        service.createAttribute(profile.uuid, [title: "title", text: "text", userId: "123", editors: ["bob"]])

        then:
        Attribute.count() == 0 // should not be persisted while in draft
        profile.attributes == null || profile.attributes.size() == 0
        profile.draft.attributes.size() == 1
        profile.draft.attributes[0].text == "text"
    }

    def "createAttribute should not update the creator but should set the original attribute when there is an original attribute in the incoming data"() {
        given:
        Vocab vocab = new Vocab(name: "vocab1")
        Term term = new Term(uuid: "1", name: "title")
        vocab.addToTerms(term)
        save vocab
        Opus opus = new Opus(glossary: new Glossary(), dataResourceUid: "dr1234", title: "title", attributeVocabUuid: vocab.uuid)
        save opus
        Profile profile = new Profile(opus: opus, scientificName: "sciName")
        save profile
        Attribute attribute = new Attribute(uuid: "1", title: term, text: "text", profile: profile, contributors: [])
        profile.addToAttributes(attribute)
        save attribute
        save profile
        Contributor fred = new Contributor(userId: "123", name: "fred")
        Contributor bob = new Contributor(userId: "987", name: "bob")
        save fred
        save bob

        service.vocabService = new VocabService()

        when:
        service.createAttribute(profile.uuid, [title: "title", text: "updatedText", userId: "123", editors: ["bob"], original: [uuid: "1"]])

        then:
        Attribute.count() == 2
        Attribute a = Attribute.list()[1]
        a.creators.size() == 0 // the creator should not be set when there is an 'original' attribute (i.e. this attribute copied from another profile
        a.original == attribute
    }

    def "getOrCreateContributor should match on userId if provided"() {
        given:
        Contributor fred = new Contributor(userId: "123", name: "fred")
        Contributor bob = new Contributor(userId: "987", name: "bob")
        save fred
        save bob

        when:
        Contributor result = service.getOrCreateContributor("bob", "123")

        then:
        result == fred
    }

    def "getOrCreateContributor should match on name if user id is not provided"() {
        given:
        Contributor fred = new Contributor(userId: "123", name: "fred")
        Contributor bob = new Contributor(userId: "987", name: "bob")
        save fred
        save bob

        when:
        Contributor result = service.getOrCreateContributor("bob", null)

        then:
        result == bob
    }

    def "getOrCreateContributor should create a new Contributor if no match is found on userId or name"() {
        given:
        Contributor fred = new Contributor(userId: "123", name: "fred")
        Contributor bob = new Contributor(userId: "987", name: "bob")
        save fred
        save bob

        when:
        Contributor result = service.getOrCreateContributor("jill", null)

        then:
        result.name == "jill"
        Contributor.count() == 3
    }

    def "saveAuthorship should not change the authorship if the incoming data does not have the authorship attribute"() {
        given:
        Opus opus = new Opus(glossary: new Glossary(), dataResourceUid: "dr1234", title: "title")
        save opus
        Authorship auth1 = new Authorship(category: new Term(name: "Acknowledgement"), text: "Fred, Jill")
        Authorship auth2 = new Authorship(category: new Term(name: "Author"), text: "Bob, Jane")
        Profile profile = new Profile(opus: opus, scientificName: "sciName", authorship: [auth1, auth2])
        save profile

        when: "the incoming data does not have the attribute"
        service.saveAuthorship(profile.uuid, [a: "bla"])

        then: "there should be no change"
        profile.authorship.contains(auth1) && profile.authorship.contains(auth2)
    }

    def "saveAuthorship should change the authorship the incoming data contains existing and new records"() {
        given:
        Opus opus = new Opus(glossary: new Glossary(), dataResourceUid: "dr1234", title: "title")
        save opus
        Authorship auth1 = new Authorship(category: new Term(name: "Acknowledgement"), text: "Fred, Jill")
        Authorship auth2 = new Authorship(category: new Term(name: "Author"), text: "Bob, Jane")
        Profile profile = new Profile(opus: opus, scientificName: "sciName", authorship: [auth1, auth2])
        save profile

        when: "the incoming data contains existing and new records"
        service.saveAuthorship(profile.uuid, [authorship: [[text: "Sarah", category: "Editor"], [category: "Author", text: "Fred, Jill"]]])

        then: "the profile's list should be updated "
        profile.authorship.each { it.text == auth1.text || it.text == "Sarah" }
    }

    def "saveAuthorship should change the authorship if the incoming data contains different records"() {
        given:
        Opus opus = new Opus(glossary: new Glossary(), dataResourceUid: "dr1234", title: "title")
        save opus
        Authorship auth1 = new Authorship(category: new Term(name: "Acknowledgement"), text: "Fred, Jill")
        Authorship auth2 = new Authorship(category: new Term(name: "Author"), text: "Bob, Jane")
        Profile profile = new Profile(opus: opus, scientificName: "sciName", authorship: [auth1, auth2])
        save profile

        when: "the incoming value is different"
        service.saveAuthorship(profile.uuid, [authorship: [[text: "Sarah", category: "Author"]]])

        then: "the profile should be replaced"
        profile.authorship.size() == 1
        profile.authorship[0].text == "Sarah"
        profile.authorship[0].category.name == "Author"
    }

    def "saveAuthorship should not change the authorship if the incoming data contains the same records"() {
        given:
        Opus opus = new Opus(glossary: new Glossary(), dataResourceUid: "dr1234", title: "title")
        save opus
        Authorship auth1 = new Authorship(category: new Term(name: "Acknowledgement"), text: "Fred, Jill")
        Authorship auth2 = new Authorship(category: new Term(name: "Author"), text: "Bob, Jane")
        Profile profile = new Profile(opus: opus, scientificName: "sciName", authorship: [auth1, auth2])
        save profile

        when: "the incoming data the same"
        service.saveAuthorship(profile.uuid, [authorship: [[text: "Bob, Jane", category: "Author"], [text: "Fred, Jill", category: "Acknowledgement"]]])

        then: "there should be no change"
        profile.authorship.size() == 2
        profile.authorship.every { it.text == auth1.text || it.text == auth2.text }
    }

    def "saveAuthorship should clear the authorship if the incoming data is empty"() {
        given:
        Opus opus = new Opus(glossary: new Glossary(), dataResourceUid: "dr1234", title: "title")
        save opus
        Authorship auth1 = new Authorship(category: new Term(name: "Acknowledgement"), text: "Fred, Jill")
        Authorship auth2 = new Authorship(category: new Term(name: "Author"), text: "Bob, Jane")
        Profile profile = new Profile(opus: opus, scientificName: "sciName", authorship: [auth1, auth2])
        save profile

        when: "the incoming attribute is empty"
        service.saveAuthorship(profile.uuid, [authorship: []])

        then: "all existing authorship should be removed"
        profile.authorship.isEmpty()
    }

    def "saveAuthorship should clear the authorship if the incoming data is null"() {
        given:
        Opus opus = new Opus(glossary: new Glossary(), dataResourceUid: "dr1234", title: "title")
        save opus
        Authorship auth1 = new Authorship(category: new Term(name: "Acknowledgement"), text: "Fred, Jill")
        Authorship auth2 = new Authorship(category: new Term(name: "Author"), text: "Bob, Jane")
        Profile profile = new Profile(opus: opus, scientificName: "sciName", authorship: [auth1, auth2])
        save profile

        when: "the incoming attribute is empty"
        service.saveAuthorship(profile.uuid, [authorship: null])

        then: "all existing authorship should be removed"
        profile.authorship.isEmpty()
    }

    def "saveAuthorship should update the profile draft if one exists"() {
        given:
        Opus opus = new Opus(glossary: new Glossary(), dataResourceUid: "dr1234", title: "title")
        save opus
        Authorship auth1 = new Authorship(category: new Term(name: "Author"), text: "Bob, Jane")
        Profile profile = new Profile(opus: opus, scientificName: "sciName", authorship: [auth1], draft: [uuid: "123", scientificName: "sciName", authorship: [auth1]])
        save profile

        when: "the incoming value is different"
        service.saveAuthorship(profile.uuid, [authorship: [[text: "Sarah", category: "Author"]]])

        then: "the profile should be replaced"
        profile.authorship.size() == 1
        profile.authorship[0].text == "Bob, Jane"
        profile.authorship[0].category.name == "Author"
        profile.draft.authorship.size() == 1
        profile.draft.authorship[0].text == "Sarah"
        profile.draft.authorship[0].category.name == "Author"
    }

    def "toggleDraftMode should create a new draft Profile if one does not exist"() {
        given:
        Opus opus = save new Opus(glossary: new Glossary(), dataResourceUid: "dr1234", title: "title")
        Profile profile = save new Profile(opus: opus, scientificName: "sciName")

        expect:
        Profile.count == 1

        when:
        service.toggleDraftMode(profile.uuid)

        then:
        Profile.count == 1
        profile.draft != null
        profile.draft != profile
        profile.draft.id != profile.id
        profile.draft.uuid == profile.uuid
    }

    def "toggleDraftMode should update the current Profile record with the draft Profile's details if one exists"() {
        given:
        Opus opus = save new Opus(glossary: new Glossary(), dataResourceUid: "dr1234", title: "title")
        Profile profile = save new Profile(uuid: "uuid1", opus: opus, scientificName: "sciName", draft: new DraftProfile(opus: opus, scientificName: "sciNameDraft", uuid: "uuid1"))

        expect:
        Profile.count == 1

        when:
        service.toggleDraftMode(profile.uuid)

        then:
        Profile.count == 1
        Profile newProfile = Profile.list().get(0)
        newProfile.draft == null
        newProfile.id == profile.id
        newProfile.uuid == profile.uuid
        newProfile.scientificName == "sciNameDraft"
    }

    def "toggleDraftModel should delete attributes that exist on the profile but not in the draft"() {
        // i.e. they have been deleted from the draft
        given:
        Vocab vocab = new Vocab(name: "vocab1")
        Term term1 = new Term(uuid: "1", name: "title1")
        Term term2 = new Term(uuid: "2", name: "title2")
        vocab.addToTerms(term1)
        vocab.addToTerms(term2)
        save vocab
        Opus opus = save new Opus(glossary: new Glossary(), dataResourceUid: "dr1234", title: "title", attributeVocabUuid: vocab.uuid)
        Profile profile = save new Profile(uuid: "uuid1", opus: opus, scientificName: "sciName")
        Attribute attribute1 = save new Attribute(uuid: "uuid1", title: term1, text: "text1", profile: profile)
        Attribute attribute2 = save new Attribute(uuid: "uuid2", title: term2, text: "text2", profile: profile)

        service.toggleDraftMode(profile.uuid)

        profile.draft.attributes.remove(0)

        save profile

        expect:
        Attribute.count == 2
        Profile.count == 1
        Profile.list()[0].attributes.size() == 2

        when:
        service.toggleDraftMode(profile.uuid)

        then:
        Attribute.count == 1
        Attribute.list()[0] == attribute2
    }
}
