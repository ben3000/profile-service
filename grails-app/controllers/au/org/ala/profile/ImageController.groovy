package au.org.ala.profile

import au.org.ala.profile.util.ImageType
import grails.converters.JSON

class ImageController extends BaseController {

    def getImageInfo() {
        if (!params.imageId) {
            badRequest "imageId is a required parameter"
        } else {
            // check for private images
            List<Profile> profiles = Profile.withCriteria {
                "privateImages" {
                    eq "imageId", params.imageId
                }
            }

            if (!profiles) {
                // check for draft private images
                profiles = Profile.withCriteria {
                    isNotNull "draft"
                    "draft" {
                            eq "privateImages.imageId", params.imageId
                    }
                }
            }

            if (!profiles) {
                // check for draft staged (i.e. public) images
                profiles = Profile.withCriteria {
                    isNotNull "draft"
                    "draft" {
                        eq "stagedImages.imageId", params.imageId
                    }
                }
            }

            if (!profiles) {
                notFound "No image was found with id ${params.imageId}"
            } else if (profiles?.size() == 1) {
                Map image = [
                        profileId: profiles[0].uuid,
                         opusId   : profiles[0].opus.uuid
                ]

                LocalImage privateImage = profiles[0].privateImages?.find { it.imageId == params.imageId } ?: profiles[0].draft?.privateImages?.find { it.imageId == params.imageId }
                LocalImage stagedImage = profiles[0].draft?.stagedImages?.find { it.imageId == params.imageId }
                if (privateImage) {
                    image.putAll(privateImage.properties)
                    image.type = ImageType.PRIVATE.name()
                } else if (stagedImage) {
                    image.putAll(stagedImage.properties)
                    image.type = ImageType.STAGED.name()
                }
                image?.remove("dbo")

                render image as JSON
            } else {
                badRequest "Non unique image id!" // should never happen
            }
        }
    }
}
