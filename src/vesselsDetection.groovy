import qupath.lib.gui.dialogs.Dialogs
import qupath.lib.objects.classes.PathClassFactory
import static qupath.lib.scripting.QP.*
import org.apache.commons.math3.stat.descriptive.DescriptiveStatistics;


// Init project
def project = getProject()
if (! project.getPixelClassifiers().contains('vesselsClassifier')) {
    Dialogs.showErrorMessage('Problem', 'No vessels classifier found')
    return
}
def classifier = project.getPixelClassifiers().get('vesselsClassifier')
def holesClass = PathClassFactory.getPathClass("Holes", makeRGB(150, 200, 200))
def vesselClass = PathClassFactory.getPathClass("Vessel", makeRGB(0, 255, 0))

// Create results file and write headers
def imageDir = new File(project.getImageList()[0].getUris()[0]).getParent()
def resultsDir = buildFilePath(imageDir, '/Results')
if (!fileExists(resultsDir)) mkdirs(resultsDir)
def resultsFile = new File(buildFilePath(resultsDir, 'detailedResults.xls'))
resultsFile.createNewFile()
resultsFile.write("Image name\tRegion name\tVessel area (um2)\n")
def globalResultsFile = new File(buildFilePath(resultsDir, 'globalResults.xls'))
globalResultsFile.createNewFile()
globalResultsFile.write("Image name\tRegion name\tRegion area (um2)\tNb holes\tHoles total area (um2)\tHoles mean area (um2)\tHoles area std\tNb vessels\tVessels total area (um2)\tVessels mean area (um2)\tVessels area std\n")

// Save annotations
def saveAnnotations(imgName) {
    def path = buildFilePath(imgName + '.annot')
    def annotations = getAnnotationObjects()
    new File(path).withObjectOutputStream {
        it.writeObject(annotations)
    }
    println('Annotations saved')
}

// Get objects area statistics (sum, mean, std)
def getObjectsAreaStatistics(objects, pixelWidth) {
    def paramsValues = null
    if (objects.size() > 0) {
        def params = new DescriptiveStatistics()
        if (objects.size() > 0) {
            for (obj in objects) {
                params.addValue(obj.getROI().getScaledArea(pixelWidth, pixelWidth))
            }
        }
        paramsValues = [params.sum, params.mean, params.standardDeviation]

    } else {
        paramsValues = [0, 0, 0]
    }
    return paramsValues
}

// Loop over images in project
for (entry in project.getImageList()) {
    def imageData = entry.readImageData()
    def server = imageData.getServer()
    def cal = server.getPixelCalibration()
    def pixelWidth = cal.getPixelWidth().doubleValue()
    def pixelUnit = cal.getPixelWidthUnit()
    def imgName = entry.getImageName()
    setBatchProjectAndImage(project, imageData)
    setImageType('FLUORESCENCE')
    println ''
    println '------ ANALYZING IMAGE ' + imgName + ' ------'

    // Find annotations
    def annotations = getAnnotationObjects()
    if (annotations.isEmpty()) {
        Dialogs.showErrorMessage("Problem", "Please create regions to analyze in image " + imgName)
        continue
    }
    def index = annotations.size()
    for (an in annotations) {
        if (an.getName() == null) {
            index++
            an.setName("Region_" + index)
        }
    }

    println '--- Finding vessels in all regions ---'
    deselectAll()
    selectObjects(annotations)
    createDetectionsFromPixelClassifier(classifier, 10, 20, 'SPLIT', 'DELETE_EXISTING')
    def holes = getDetectionObjects().findAll({it.getPathClass() == holesClass})
    def vessels = getDetectionObjects().findAll({it.getPathClass() == vesselClass})

    for (an in annotations) {
        println '--- Saving results for region ' + an.getName() + ' ---'

        // Find annotation area
        def regionArea = an.getROI().getScaledArea(pixelWidth, pixelWidth)
        println 'Region area = ' + regionArea + ' ' + pixelUnit + '2'

        // Find holes in annotation
        def holesInAn = holes.findAll({it.getParent() == an
                                                        && it.getROI().getScaledArea(pixelWidth, pixelWidth) > 1000})
        println 'Nb of holes detected = ' + holesInAn.size()

        // Find vessels in annotation
        def vesselsInAn = vessels.findAll({it.getParent() == an})
        println 'Nb of vessels detected = ' + vesselsInAn.size()

        // Clear all detections that are not vessels (tissue, background...)
        an.clearPathObjects()
        an.addPathObjects(holesInAn)
        an.addPathObjects(vesselsInAn)
        resolveHierarchy()

        // Save annotation and detections
        clearAllObjects()
        addObject(an)
        saveAnnotations(buildFilePath(resultsDir, imgName+"_"+an.getName()))

        // Write results in files
        for (vessel in vesselsInAn) {
            def results = imgName + "\t" + an.getName() + "\t" + vessel.getROI().getScaledArea(pixelWidth, pixelWidth) + "\n"
            resultsFile << results
        }
        def holeStatistics = getObjectsAreaStatistics(holesInAn, pixelWidth)
        def vesselStatistics = getObjectsAreaStatistics(vesselsInAn, pixelWidth)
        def globalResults = imgName + "\t" + an.getName() + "\t" + regionArea + "\t" + holesInAn.size() + "\t" + holeStatistics[0] + "\t" +
                holeStatistics[1] + "\t" + holeStatistics[2] + "\t" + vesselsInAn.size() + "\t" + vesselStatistics[0] + "\t" + vesselStatistics[1] + "\t" + vesselStatistics[2] + "\n"
        globalResultsFile << globalResults
    }
    clearAllObjects()
}
println '--- All done! ---'