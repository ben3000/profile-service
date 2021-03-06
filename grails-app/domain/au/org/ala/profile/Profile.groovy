package au.org.ala.profile

import groovy.transform.EqualsAndHashCode
import groovy.transform.ToString
import org.bson.types.ObjectId

import javax.persistence.Transient

@EqualsAndHashCode
@ToString
class Profile {

    ObjectId id
    String uuid
    String guid                 //taxon GUID / LSID
    String scientificName
    String nameAuthor
    String fullName
    String rank
    String nslNameIdentifier
    String nslNomenclatureIdentifier
    String nslProtologue

    @Transient
    boolean privateMode = false

    Name matchedName

    String primaryImage
    List<String> excludedImages
    List<String> specimenIds
    List<Authorship> authorship
    List<Classification> classification
    List<Link> links
    List<Link> bhlLinks
    List<Bibliography> bibliography
    List<Publication> publications

    List<StagedImage> stagedImages = null // this is only used when dealing with draft profiles

    String lastAttributeChange

    Date dateCreated
    String createdBy
    Date lastUpdated
    String lastUpdatedBy

    DraftProfile draft

    String archiveComment
    Date archivedDate
    String archivedBy
    String archivedWithName

    static embedded = ['authorship', 'classification', 'draft', 'links', 'bhlLinks', 'publications', 'bibliography', 'matchedName']

    static hasMany = [attributes: Attribute]

    static belongsTo = [opus: Opus]

    static constraints = {
        nameAuthor nullable: true
        fullName nullable: true
        guid nullable: true
        primaryImage nullable: true
        excludedImages nullable: true
        specimenIds nullable: true
        classification nullable: true
        nslNameIdentifier nullable: true
        nslNomenclatureIdentifier nullable: true
        nslProtologue nullable: true
        rank nullable: true
        draft nullable: true
        matchedName nullable: true
        createdBy nullable: true
        lastUpdatedBy nullable: true
        lastAttributeChange nullable: true
        archiveComment nullable: true
        archivedDate nullable: true
        archivedBy nullable: true
        archivedWithName nullable: true
    }

    static mapping = {
        version false
        attributes cascade: "all-delete-orphan"
    }

    def beforeValidate() {
        if (uuid == null) {
            uuid = UUID.randomUUID().toString()
        }
    }
}
