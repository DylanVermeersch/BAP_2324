/*
 * sortBuildList - sorts a build list by rank property values
 */
def sortBuildList(List<String> buildList) {
    List<String> sortedList = []
    TreeMap<Integer,List<String>> rankings = new TreeMap<Integer,List<String>>()
    List<String> fileBuildOrder = props.cobol_fileBuildOrder.split(',')
    List<String> unranked = new ArrayList<String>()

    // sort buildFiles by rank
    buildList.each { buildFile ->
        // create LogicalFile to determine the properties isFSUB(), isDB2(), ...
        String dependencySearch = props.getFileProperty('cobol_dependencySearch', buildFile)
        SearchPathDependencyResolver dependencyResolver = new SearchPathDependencyResolver(dependencySearch)
        LogicalFile logicalFile = createLogicalFile(dependencyResolver, buildFile)
        def flag = false
        
        for (rank in fileBuildOrder) {
            def index = fileBuildOrder.indexOf(rank)
            
            switch (rank) {
                case "FSUB":
                    List<String> ranking = rankings.get(index)
                    if (!ranking) {
                        ranking = new ArrayList<String>()
                        rankings.put(index,ranking)
                    }
                    if (isFSUB(logicalFile)) {
                        ranking << buildFile
                        flag = true
                    }
                    break;
                case "SUB":
                    List<String> ranking = rankings.get(index)
                    if (!ranking) {
                        ranking = new ArrayList<String>()
                        rankings.put(index,ranking)
                    }
                    if (isSUB(logicalFile)) {
                        ranking << buildFile
                        flag = true
                    }
                    break;
                case "MAIN":
                    List<String> ranking = rankings.get(index)
                    if (!ranking) {
                        ranking = new ArrayList<String>()
                        rankings.put(index,ranking)
                    }                       
                    if (isMain(logicalFile)) {
                        ranking << buildFile
                        flag = true
                    }
                    break;
                default:
                    List<String> ranking = rankings.get(fileBuildOrder.size())
                    if (!ranking) {
                        ranking = new ArrayList<String>()
                        rankings.put(index,ranking)
                    }
                    ranking << buildFile
                    flag = true
                    break;
            }

            println("Show the ranking after $buildFile: $rankings")
            if (flag)
                break;
        }

        if (!flag) 
            unranked << buildFile
    }

    // loop through rank keys adding sub lists (TreeMap automatically sorts keySet)
    rankings.keySet().each { key ->
        List<String> ranking = rankings.get(key)
        if (ranking)
            sortedList.addAll(ranking)
    }

    sortedList.addAll(unranked)

    return sortedList
}

/**
 * Method to create the logical file using SearchPathDependencyResolver
 *  evaluates if it should resolve file flags for resolved dependencies
 */

def createLogicalFile(SearchPathDependencyResolver spDependencyResolver, String buildFile) {
    
    LogicalFile logicalFile
    
    if (props.resolveSubsystems && props.resolveSubsystems.toBoolean()) {
        // include resolved dependencies to define file flags of logicalFile
        logicalFile = spDependencyResolver.resolveSubsystems(buildFile,props.workspace)
    }
    else {
        logicalFile = SearchPathDependencyResolver.getLogicalFile(buildFile,props.workspace)
    }

    return logicalFile

}

/*
 * isDev - tests to see if the program is a main program. If the logical file is false, then
 * check to see if there is a file property.
 */
def isDev(LogicalFile logicalFile) {
    boolean isDev = logicalFile.isDev()
    if (!isDev) {
        String devFlag = props.getFileProperty('isDev', logicalFile.getFile())
        String prodFlag = props.getFileProperty('isProd', logicalFile.getFile())
        if (prodFlag && (prodFlag == devFlag || !prodFlag.toBoolean())) {
            logicalFile.setDev(true)
            logicalFile.setProd(false)
        }
        else {
            logicalFile.setDev(true)
            logicalFile.setProd(false)
        }
    }

    return logicalFile.isDev()
}

/*
 * isProd - tests to see if the program is a main program. If the logical file is false, then
 * check to see if there is a file property.
 */
def isProd(LogicalFile logicalFile) {
    boolean isProd = logicalFile.isProd()
    if (!isProd && !isDev(logicalFile)) {
        logicalFile.setProd(true)
        logicalFile.setDev(false)
    }

    return logicalFile.isProd()
}

/*
 * isMain - tests to see if the program is a main program. If the logical file is false, then
 * check to see if there is a file property.
 */
def isMain(LogicalFile logicalFile) {
    boolean isMain = logicalFile.isMain()
    if (!isMain && logicalFile.isFSUB() == logicalFile.isSUB()) {
        String mainFlag = props.getFileProperty('isMain', logicalFile.getFile())
        if (mainFlag && mainFlag.toBoolean()) {
            logicalFile.setMain(mainFlag.toBoolean())
            logicalFile.setFSUB(false)
            logicalFile.setSUB(false)
        }
        else if (props.getFileProperty('isFSUB', logicalFile.getFile()) == props.getFileProperty('isSUB', logicalFile.getFile())) {
                logicalFile.setMain(true)
                logicalFile.setFSUB(false)
                logicalFile.setSUB(false)
        }
    }

    return logicalFile.isMain()
}

/*
 * isFSUB - tests to see if the program is a fsub program. If the logical file is false, then
 * check to see if there is a file property.
 */
def isFSUB(LogicalFile logicalFile) {
    boolean isFSUB = logicalFile.isFSUB()
    if (!isFSUB && !isMain(logicalFile)) {
        String fsubFlag = props.getFileProperty('isFSUB', logicalFile.getFile())
        if (fsubFlag) {
            logicalFile.setFSUB(fsubFlag.toBoolean())

            if (fsubFlag.toBoolean()) {    
                logicalFile.setSUB(false)
                logicalFile.setMain(false)
            }
        }
    }

    return logicalFile.isFSUB()
}

/*
 * isSUB - tests to see if the program is a sub program. If the logical file is false, then
 * check to see if there is a file property.
 */
def isSUB(LogicalFile logicalFile) {
    boolean isSUB = logicalFile.isSUB()
    if (!isSUB && !isMain(logicalFile)) {
        String subFlag = props.getFileProperty('isSUB', logicalFile.getFile())
        if (subFlag) {
            logicalFile.setSUB(subFlag.toBoolean())
            
            if (subFlag.toBoolean()) {
                logicalFile.setFSUB(false)
                logicalFile.setMain(false)
            }
        }
    }

    return logicalFile.isSUB()
}

/*
 * isDB2 - tests to see if the program is a DB2 program. If the logical file is false, then
 * check to see if there is a file property.
 */
def isDB2(LogicalFile logicalFile) {
    boolean isDB2 = logicalFile.isDB2()
    if (!isDB2) {
        String db2Flag = props.getFileProperty('isDB2', logicalFile.getFile())
        if (db2Flag)
            logicalFile.setDB2(db2Flag.toBoolean())
    }

    return logicalFile.isDB2()
}

/*
 * isIMS - tests to see if the program is a DL/I program. If the logical file is false, then
 * check to see if there is a file property.
 */
def isIMS(LogicalFile logicalFile) {
    boolean isIMS = logicalFile.isIMS()
    if (!isIMS) {
        String imsFlag = props.getFileProperty('isIMS', logicalFile.getFile())
        if (imsFlag)
            logicalFile.setIMS(imsFlag.toBoolean())
    }

    return logicalFile.isIMS()
}