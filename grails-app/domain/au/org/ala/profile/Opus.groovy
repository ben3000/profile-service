package au.org.ala.profile

class Opus {

    String uuid
    String title
    String dataResourceUid
    List<String> imageSources            // a list of drs that are providing images we can include
    List<String>  recordSources         // a list of drs that are providing images we can include
    String logoUrl
    String bannerUrl
    String mapAttribution // e.g. AVH (CHAH)
    String biocacheUrl    // e.f  http://avh.ala.org.au/
    String biocacheName    ///e.g. Australian Virtual Herbarium
    String attributeVocabUuid
    Boolean enablePhyloUpload = true
    Boolean enableOccurrenceUpload = true
    Boolean enableTaxaUpload = true
    Boolean enableKeyUpload = true

    static constraints = {
        logoUrl nullable: true
        bannerUrl nullable: true
        attributeVocabUuid nullable: true
        enablePhyloUpload nullable: true
        enableOccurrenceUpload nullable: true
        enableTaxaUpload nullable: true
        enableKeyUpload nullable: true
        mapAttribution nullable: true
        biocacheUrl nullable: true
        biocacheName nullable: true
    }

    static mapping = {
        version false
    }
}