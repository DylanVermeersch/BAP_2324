@groovy.transform.BaseScript com.ibm.dbb.groovy.ScriptLoader baseScript
import com.ibm.dbb.build.DBBConstants.CopyMode
import com.ibm.dbb.metadata.*
import com.ibm.dbb.dependency.*
import com.ibm.dbb.build.*
import groovy.transform.*
import com.ibm.jzos.ZFile
import com.ibm.dbb.build.report.*
import com.ibm.dbb.build.report.records.*


// define script properties
@Field BuildProperties props = BuildProperties.getInstance()
@Field def buildUtils = loadScript(new File("${props.zAppBuildDir}/utilities/BuildUtilities.groovy"))
@Field def impactUtils = loadScript(new File("${props.zAppBuildDir}/utilities/ImpactUtilities.groovy"))
@Field def bindUtils = loadScript(new File("${props.zAppBuildDir}/utilities/BindUtilities.groovy"))
@Field String[] loadDatasets = []
@Field String[] db2BindDummy = props.db2BindDummyJCL.split('\\(')
@Field def dummyBindDataset = db2BindDummy[0]
@Field def dummyBindMember = db2BindDummy[1].substring(0, db2BindDummy[1].length() -1)
    
println("** Building ${argMap.buildList.size()} ${argMap.buildList.size() == 1 ? 'file' : 'files'} mapped to ${this.class.getName()}.groovy script")

// verify required build properties
buildUtils.assertBuildProperties(props.cobol_requiredBuildProperties)

// create language datasets
def langQualifier = "cobol"
buildUtils.createLanguageDatasets(langQualifier)

// sort the build list based on build file rank if provided
List<String> sortedList = buildUtils.sortBuildList(argMap.buildList.sort())
int currentBuildFileNumber = 1

if (buildListContainsTests(sortedList)) {
    langQualifier = "cobol_test"
    buildUtils.createLanguageDatasets(langQualifier)
}

// iterate through build list
sortedList.each { buildFile ->
    println "*** (${currentBuildFileNumber++}/${sortedList.size()}) Building file $buildFile"

    // Check if this a testcase
    isZUnitTestCase = buildUtils.isGeneratedzUnitTestCaseProgram(buildFile)

    // configure dependency resolution and create logical file    
    String dependencySearch = props.getFileProperty('cobol_dependencySearch', buildFile)
    SearchPathDependencyResolver dependencyResolver = new SearchPathDependencyResolver(dependencySearch)
    
    // copy build file and dependency files to data sets
    if(isZUnitTestCase) {
        buildUtils.copySourceFiles(buildFile, props.cobol_testcase_srcPDS, null, null, null)
    } else {
        buildUtils.copySourceFiles(buildFile, props.cobol_srcPDS, 'cobol_dependenciesDatasetMapping', props.cobol_dependenciesAlternativeLibraryNameMapping, dependencyResolver)
    }

    // Get logical file
    LogicalFile logicalFile = buildUtils.createLogicalFile(dependencyResolver, buildFile)

    // print logicalFile details and overrides
    if (props.verbose) buildUtils.printLogicalFileAttributes(logicalFile)
    buildUtils.printLogicalFileAttributes(logicalFile)
    
    // create mvs commands
    String member = CopyToPDS.createMemberName(buildFile)
    String needsLinking = props.getFileProperty('cobol_linkEdit', buildFile)
    
    File logFile = new File( props.userBuild ? "${props.buildOutDir}/${member}.log" : "${props.buildOutDir}/${member}.cobol.log")
    if (logFile.exists())
        logFile.delete()
    
    MVSExec compile = createCompileCommand(buildFile, logicalFile, member, logFile)
    MVSExec linkEdit
    if (needsLinking.toBoolean()) linkEdit = createLinkEditCommand(buildFile, logicalFile, member, logFile)
    MVSExec deleteObj = createObjectDeleteCommand(buildFile,props.cobol_objPDS, member, logFile)

    // execute mvs commands in a mvs job
    MVSJob job = new MVSJob()
    job.start()

    if (loadDatasets.size() < 1) {
        bindFlag = false
        String errorMsg = "*! The program needs at least one load library, none were given!"
        println(errorMsg)
        props.error = "true"
        buildUtils.updateBuildResult(errorMsg:errorMsg,logs:["${member}.log":logFile])
    } 
    else {

        // compile the cobol program
        int rc = compile.execute()
        int maxRC = props.getFileProperty('cobol_compileMaxRC', buildFile).toInteger()

        boolean bindFlag = true

        if (rc > maxRC) {
            bindFlag = false
            String errorMsg = "*! The compile return code ($rc) for $buildFile exceeded the maximum return code allowed ($maxRC)"
            println(errorMsg)
            props.error = "true"
            buildUtils.updateBuildResult(errorMsg:errorMsg,logs:["${member}.log":logFile])
        }
        else { 
            // if this program needs to be link edited . . .
            
            // Store db2 bind information as a generic property record in the BuildReport
            String generateDb2BindInfoRecord = props.getFileProperty('generateDb2BindInfoRecord', buildFile)
            if (buildUtils.isDB2(logicalFile) && generateDb2BindInfoRecord.toBoolean() ){
                PropertiesRecord db2BindInfoRecord = buildUtils.generateDb2InfoRecord(buildFile)
                BuildReportFactory.getBuildReport().addRecord(db2BindInfoRecord)
            }
            
            if (needsLinking.toBoolean()) {
                rc = linkEdit.execute()
                maxRC = props.getFileProperty('cobol_linkEditMaxRC', buildFile).toInteger()

                if (rc > maxRC) {
                    bindFlag = false
                    String errorMsg = "*! The link edit return code ($rc) for $buildFile exceeded the maximum return code allowed ($maxRC)"
                    println(errorMsg)
                    props.error = "true"
                    buildUtils.updateBuildResult(errorMsg:errorMsg,logs:["${member}.log":logFile])
                }
                else {               
                    // only scan the load module if load module scanning turned on for file
                    if(!props.userBuild && !isZUnitTestCase){
                        String scanLoadModule = props.getFileProperty('cobol_scanLoadModule', buildFile)
                        if (scanLoadModule && scanLoadModule.toBoolean() && loadDatasets.size() > 0) {
                            def directory = "/DMNSO/C0/data/dbb/load/${logicalFile.getLname()}.load"
                            impactUtils.saveStaticLinkDependencies(buildFile, loadDatasets[0], logicalFile)
                            
                            if (loadDatasets.size() > 1) {
                                for (loadDataset : loadDatasets) {
                                    if (loadDataset == loadDatasets[0]) {
                                        def copyLoad = new CopyToHFS()
                                        copyLoad.copyMode(CopyMode.LOAD)
                                        copyLoad.setDataset(loadDatasets[0])
                                        copyLoad.setMember(member)
                                        copyLoad.setFile(new File("$directory"))
                                        copyLoad.execute()
                                    }
                                    else {
                                        def copyFile = new CopyToPDS()
                                        copyFile.copyMode(CopyMode.LOAD)
                                        copyFile.setFile(new File(directory))
                                        copyFile.setDataset(loadDataset)
                                        copyFile.execute()
                                    }
                                }
                            }
                        }
                    }

                    // add import statements to IMPORTS if necessary
                    
                    if (logicalFile.isFSUB()) {
                        addImportStatements(member)

                        // delete the object in hlq.cob.obj
                        rc = deleteObj.execute()
                        maxRC = 0

                        if (rc > maxRC) {
                            bindFlag = false
                            String errorMsg = "*! The deletion of object return code ($rc) for $buildFile exceeded the maximum return code allowed ($maxRC)"
                            println(errorMsg)
                            props.error = "true"
                            buildUtils.updateBuildResult(errorMsg:errorMsg,logs:["${member}.log":logFile])
                        }
                    }

                    //perform Db2 Bind only on genrateDb2BindInfoRecord property
                    String db2Bind = props.getFileProperty('generateDb2BindInfoRecord', buildFile)

                    if (bindFlag && logicalFile.isDB2() && db2Bind && db2Bind.toBoolean()) {
                        // Setting the maximum allowed return code for the bind process
                        int bindMaxRC = props.db2Bind_maxRC.toInteger()

                        JCLExec bindPackage
                        JCLExec bindPlan
                        if (logicalFile.isDB2()) {
                            bindPlan = createBindPlanCommand(buildFile, member)
                            bindPackage = createBindPackageCommand(buildFile, member, ( !props.db2Owner ) ? System.getProperty("user.name") : props.db2Owner)
                        }

                        // if no owner is set, use the user.name as package owner
                        def owner = ( !props.db2Owner ) ? System.getProperty("user.name") : props.db2Owner

                        // Perform bind plan through JCLExec
                        bindPlan.execute()
                        String planRC = bindPlan.getMaxRC()
                        def rcBindPlan = planRC.substring(3)?.isInteger() ? planRC.substring(3) as int : null
                        
                        // Store and show job info 
                        println("Plan Bind for $member\nJOB ID = ${bindPlan.getSubmittedJobId()} - JOB Name = ${bindPlan.getSubmittedJobName()}")
                        bindPlan.saveOutput(new File("${props.buildOutDir}/${member}_planjcl.log"))
                        
                        if (rcBindPlan == null) {
                                String errorMsg = "*! The bind plan returned an error: $planRC for $buildFile check the output of the logs."
                                println(errorMsg)
                                props.error = "true"
                                buildUtils.updateBuildResult(errorMsg:errorMsg,logs:["${member}.log":logFile,"${props.buildOutDir}/${member}.err.log":logFile])
                            }

                        if (rcBindPlan > bindMaxRC) {
                            String errorMsg = "*! The bind plan return code ($rcBindPlan) for $buildFile exceeded the maximum return code allowed ($bindMaxRC)"
                            println(errorMsg)
                            props.error = "true"
                            buildUtils.updateBuildResult(errorMsg:errorMsg,logs:["${member}.log":logFile,"${props.buildOutDir}/${member}.err.log":logFile])
                        }

                        // Perform the bind package through JCLExec
                        bindPackage.execute()
                        String packageRC = bindPackage.getMaxRC()
                        def rcBindPackage = packageRC.substring(3)?.isInteger() ? packageRC.substring(3) as int : null

                        // Store and show job info
                        println("Package Bind for $member\nJOB ID = ${bindPackage.getSubmittedJobId()} - JOB Name = ${bindPackage.getSubmittedJobName()}")
                        bindPackage.saveOutput(new File("${props.buildOutDir}/${member}_packagejcl.log"))
                        
                        if (rcBindPackage == null) {
                            String errorMsg = "*! The bind package returned an error: $packageRC for $buildFile check the output of the logs."
                            println(errorMsg)
                            props.error = "true"
                            buildUtils.updateBuildResult(errorMsg:errorMsg,logs:["${member}.log":logFile,"${props.buildOutDir}/${member}.err.log":logFile])
                        }

                        if (rcBindPackage > bindMaxRC) {
                            String errorMsg = "*! The bind package return code ($rcBindPackage) for $buildFile exceeded the maximum return code allowed ($bindMaxRC)"
                            println(errorMsg)
                            props.error = "true"
                            buildUtils.updateBuildResult(errorMsg:errorMsg,logs:["${member}.log":logFile,"${props.buildOutDir}/${member}.err.log":logFile])
                        }

                        // clean up DBRM lib
                        MVSExec deleteDBRM = createObjectDeleteCommand(buildFile,props.cobol_dbrmPDS, member, logFile)
                        
                        // delete the object in hlq.cob.obj
                        rc = deleteDBRM.execute()
                        maxRC = 0

                        if (rc > maxRC) {
                            bindFlag = false
                            String errorMsg = "*! The deletion of the DBRM return code ($rc) for $buildFile exceeded the maximum return code allowed ($maxRC)"
                            println(errorMsg)
                            props.error = "true"
                            buildUtils.updateBuildResult(errorMsg:errorMsg,logs:["${member}.log":logFile])
                        }
                    }
                }
            }
        }
    }
    // clean up passed DD statements
    job.stop()
}

// end script


//********************************************************************
//* Method definitions
//********************************************************************

/*
 * createVersion - Creates a version for DB2 BIND PACKAGE
 * looks like $$$DBRMVR-TSTCOB2-2023112858001
 * $$$DBRMV(R) -> either only DBRMVR or also DBRMVS => only in double compile ex. new PL/I compiler testing for behavourial changes
 * TSTCOB2 -> program name ex. program TESTHFD will have TESTHFD in it's version string
 * 2023112858001 -> first 8 digits (20231128) are the date in format yyyyMMdd
 *               -> last 5 digits (58001) is the time in seconds since last midnight: hours * 3600 + minutes * 60 + seconds  
 */
def createVersion(LogicalFile logicalFile) {
    def dateTime = new Date()
    def tz = TimeZone.getTimeZone('GMT+1')
    def (hours, minutes, seconds) = [dateTime.format('HH', tz) as Integer, dateTime.format('mm', tz) as Integer, dateTime.format('ss', tz) as Integer]
    def packtime = "${dateTime.format( 'yyyyMMdd', tz )}${hours * 3600 + minutes * 60 + seconds}"
    def version = "\$\$\$DBRMVR-${logicalFile.getLname()}-$packtime"

    return version
    
}


/*
 * createCobolParms - Builds up the Cobol compiler parameter list from build and file properties
 * legend: Type of program | IMS? | DB2? | ==> isMYY = Main, using IMS and DB2
 *                                         ==> isMNY = Main, using DB2, no IMS
 *                                         ==> isFNY = FSUB, using DB2, no IMS
 *                                         ==> isSNN = SUB, no DB2, no IMS
 *                                         ==> ...
 */
def createCobolParms(String buildFile, LogicalFile logicalFile) {
    def omgeving = (buildUtils.isDev(logicalFile) && !(buildUtils.isProd(logicalFile))) || (!(buildUtils.isDev(logicalFile)) && !(buildUtils.isProd(logicalFile))) ? "O" : "P"
    def defaultParms = [
        'MNN':"DEV.PARMLIB(COBMNN${omgeving})", //DONE
        'MNY':"DEV.PARMLIB(COBMNY${omgeving})", //DONE
        'MYN':"DEV.PARMLIB(COBMYN${omgeving})", //DONE*
        'MYY':"DEV.PARMLIB(COBMYY${omgeving})", //DONE*
        'FNN':"DEV.PARMLIB(COBFNN${omgeving})", //DONE
        'FNY':"DEV.PARMLIB(COBFNY${omgeving})", //DONE
        'FYN':"DEV.PARMLIB(COBFYN${omgeving})", //DONE*
        'FYY':"DEV.PARMLIB(COBFYY${omgeving})", //DONE*
        'SNN':"DEV.PARMLIB(COBSNN${omgeving})", //DONE
        'SNY':"DEV.PARMLIB(COBSNY${omgeving})", //DONE
        'SYN':"DEV.PARMLIB(COBSYN${omgeving})", //DONE*
        'SYY':"DEV.PARMLIB(COBSYY${omgeving})", //DONE*
    ]
    def extraParms = props.getFileProperty('cobol_extraCompileParms', buildFile) ?: ""
    def errPrefixOptions = props.getFileProperty('cobol_compileErrorPrefixParms', buildFile) ?: ""
    def compileDebugParms = props.getFileProperty('cobol_compileDebugParms', buildFile)
    def version = ""
    def parms = ""
    def key = ""

    // Making a key variable so we can use the right compile parms and @MACRO2
    if (buildUtils.isMain(logicalFile))
            key = "M" 
    else if (buildUtils.isFSUB(logicalFile))
            key = "F" 
    else if (buildUtils.isSUB(logicalFile))
        key = "S"
    else {
        key = "M"
        logicalFile.setMain(true)
    }

    if (buildUtils.isIMS(logicalFile)) {
            key = "${key}Y"
    }
    else
        key = "${key}N"
    if (buildUtils.isDB2(logicalFile)) {
        key = "${key}Y"
        version = "SQL(\'VERSION(${createVersion(logicalFile)})\')" 
    }
    else
        key = "${key}N"

    println("key heeft de waarde: $key voor logicalFile: $logicalFile")
    parms = "OPTFILE $extraParms $version"
    println("Dit resulteert in de volgende parms: ${parms} en volgende dataset in de SYSOPTF: ${defaultParms[key]}")

    if (props.errPrefix)
        parms = "$parms,$errPrefixOptions"

    // add debug options
    if (props.debug)  {
        parms = "$parms,$compileDebugParms"
    }

    if (parms.startsWith(','))
        parms = parms.drop(1)

    if (props.verbose) println "*** Cobol compiler parms for $buildFile = $parms"
    return [defaultParms[key], parms]
}

/*
 * createBindPackageCommand - creates a JCLExec command to package bind the program (buildFile)
 */
def createBindPackageCommand(String buildFile, String member, String owner) {
    String jclDataset = "${System.getProperty('user.name')}.SOURCE.CNTL"
    String jclPackageMember = 'PACKBIND'

    clistDSN = bindUtils.makeBindPackage(buildFile,
                            props.cobol_dbrmPDS,
                            props.buildOutDir,
                            props.getFileProperty('db2Subsys', buildFile),
                            owner,
                            props.getFileProperty('db2Qual', buildFile))

    jclLocation = new File("${props.buildOutDir}/${member}_packagedummy.jcl")
    
    // Getting the template file 
    def dummyBind = new CopyToHFS()
    dummyBind.setDataset(dummyBindDataset)
    dummyBind.setMember(dummyBindMember)
    dummyBind.setFile(jclLocation)
    dummyBind.execute()

    //Altering the template file
    jclLocation.append("//SYSTSIN  DD DSN=$clistDSN,\n//         DISP=SHR")

    // Copying the altered file to PDS
    new CopyToPDS().file(jclLocation).dataset(jclDataset).member(jclPackageMember).execute()

    // Assigning the copied JCL 
    JCLExec bindPackage = new JCLExec()
    bindPackage.setDataset(jclDataset)
    bindPackage.setMember(jclPackageMember)

    return bindPackage
}

/*
 * createBindPlanCommand - creates a JCLExec command to plan bind the program (buildFile)
 */
def createBindPlanCommand(String buildFile, String member) {
    String jclDataset = "${System.getProperty('user.name')}.SOURCE.CNTL"
    String jclPlanMember = 'PLANBIND'

    clistDSN = bindUtils.makeBindPlan(buildFile,
                            props.cobol_dbrmPDS,
                            props.buildOutDir,
                            props.getFileProperty('db2Subsys', buildFile))

    jclLocation = new File("${props.buildOutDir}/${member}_plandummy.jcl")
    
    // Getting the template file 
    def dummyBind = new CopyToHFS()
    dummyBind.setDataset(dummyBindDataset)
    dummyBind.setMember(dummyBindMember)
    dummyBind.setFile(jclLocation)
    dummyBind.execute()

    //Altering the template file
    jclLocation.append("//SYSTSIN  DD DSN=$clistDSN,\n//         DISP=SHR")

    // Copying the altered file to PDS
    new CopyToPDS().file(jclLocation).dataset(jclDataset).member(jclPlanMember).execute()

    // Assigning the copied JCL 
    JCLExec bindPlan = new JCLExec()
    bindPlan.setDataset(jclDataset)
    bindPlan.setMember(jclPlanMember)

    return bindPlan
}

/*
 * createCompileCommand - creates a MVSExec command for compiling the COBOL program (buildFile)
 */
def createCompileCommand(String buildFile, LogicalFile logicalFile, String member, File logFile) {
    String compiler = props.getFileProperty('cobol_compiler', buildFile)
    
    (parmFile, parms) = createCobolParms(buildFile, logicalFile)

    // get the right load PDSEs in place
    if (logicalFile.isFSUB())
        loadDatasets = props.cobol_loadDatasetsFSUB.split(',')
    else if (logicalFile.isSUB())
        loadDatasets = props.cobol_loadDatasetsSUB.split(',')
    else
        loadDatasets = props.cobol_loadDatasets.split(',')

    println("Show the load library datasets: $loadDatasets")

    // define the MVSExec command to compile the program
    MVSExec compile = new MVSExec().file(buildFile).pgm(compiler).parm(parms)

    // add DD statements to the compile command
    if (isZUnitTestCase){
        compile.dd(new DDStatement().name("SYSIN").dsn("${props.cobol_testcase_srcPDS}($member)").options('shr').report(true))
    }
    else
    {
        compile.dd(new DDStatement().name("SYSIN").dsn("${props.cobol_srcPDS}($member)").options('shr').report(true))
    }
    
    compile.dd(new DDStatement().name("SYSPRINT").options(props.cobol_printTempOptions))
    compile.dd(new DDStatement().name("SYSOPTF").dsn(parmFile).options('shr'))
    compile.dd(new DDStatement().name("SYSDEFSD").options(props.cobol_tempOptions))
    compile.dd(new DDStatement().name("SYSMDECK").options(props.cobol_tempOptions))

    (1..17).toList().each { num ->
        compile.dd(new DDStatement().name("SYSUT$num").options(props.cobol_tempOptions))
    }

    // define object dataset allocation
    compile.dd(new DDStatement().name("SYSLIN").dsn("${props.cobol_objPDS}($member)").options('shr').output(true))

    // add a syslib to the compile command with optional bms output copybook and concatenation
    compile.dd(new DDStatement().name("SYSLIB").dsn(props.cobol_cpyPDS).options("shr"))
    
    // add additional datasets with dependencies based on the dependenciesDatasetMapping
    PropertyMappings dsMapping = new PropertyMappings('cobol_dependenciesDatasetMapping')
    dsMapping.getValues().each { targetDataset ->
        // exclude the defaults cobol_cpyPDS and any overwrite in the alternativeLibraryNameMap
        if (targetDataset != 'cobol_cpyPDS')
            compile.dd(new DDStatement().dsn(props.getProperty(targetDataset)).options("shr"))
    }

    // add custom concatenation
    def compileSyslibConcatenation = props.getFileProperty('cobol_compileSyslibConcatenation', buildFile) ?: ""
    def compileSyslibAddConcatenation = props.cobol_compileSyslibAddConcat
    if (compileSyslibConcatenation && compileSyslibAddConcatenation)
        compileSyslibConcatenation = compileSyslibConcatenation + ',' + compileSyslibAddConcatenation

    if (logicalFile.isDB2()) 
        compileSyslibConcatenation = 'DEV.DCLGEN,PROD.DCLGEN,GEBR.DCLGEN,' + compileSyslibConcatenation

    println("compileSyslibConcat: ${compileSyslibConcatenation}")
    if (compileSyslibConcatenation) {
        def String[] syslibDatasets = compileSyslibConcatenation.split(',');
        for (String syslibDataset : syslibDatasets ) 
            compile.dd(new DDStatement().dsn(syslibDataset).options("shr"))
    }
    
    // add subsystem libraries
    if (buildUtils.isMQ(logicalFile))
        compile.dd(new DDStatement().dsn(props.SCSQCOBC).options("shr"))
        
    // add additional zunit libraries
    if (isZUnitTestCase)
    compile.dd(new DDStatement().dsn(props.SBZUSAMP).options("shr"))

    // add a tasklib to the compile command with optional IMS, DB2, and IDz concatenations
    String compilerVer = props.getFileProperty('cobol_compilerVersion', buildFile)
    compile.dd(new DDStatement().name("TASKLIB").dsn(props."SIGYCOMP_$compilerVer").options("shr"))
    if (buildUtils.isDB2(logicalFile)) {
        if (props.SDSNEXIT) compile.dd(new DDStatement().dsn(props.SDSNEXIT).options("shr"))
        compile.dd(new DDStatement().dsn(props.SDSNLOAD).options("shr"))
    }
    if (props.SFELLOAD)
        compile.dd(new DDStatement().dsn(props.SFELLOAD).options("shr"))

    // add optional DBRMLIB if build file contains DB2 code
    if (buildUtils.isDB2(logicalFile))
        compile.dd(new DDStatement().name("DBRMLIB").dsn("$props.cobol_dbrmPDS($member)").options('shr').output(true).deployType('DBRM'))


    // adding alternate library definitions
    if (props.cobol_dependenciesAlternativeLibraryNameMapping) {
        alternateLibraryNameAllocations = buildUtils.parseJSONStringToMap(props.cobol_dependenciesAlternativeLibraryNameMapping)
        alternateLibraryNameAllocations.each { libraryName, datasetDefinition ->
            datasetName = props.getProperty(datasetDefinition)
            if (datasetName) {
                compile.dd(new DDStatement().name(libraryName).dsn(datasetName).options("shr"))
            }
            else {
                String errorMsg = "*! Cobol.groovy. The dataset definition $datasetDefinition could not be resolved from the DBB Build properties."
                println(errorMsg)
                props.error = "true"
                buildUtils.updateBuildResult(errorMsg:errorMsg)
            }
        }
    }

    // add IDz User Build Error Feedback DDs
    if (props.errPrefix) {
        compile.dd(new DDStatement().name("SYSADATA").options("DUMMY"))
        // SYSXMLSD.XML suffix is mandatory for IDZ/ZOD to populate remote error list
        compile.dd(new DDStatement().name("SYSXMLSD").dsn("${props.hlq}.${props.errPrefix}.SYSXMLSD.XML").options(props.cobol_compileErrorFeedbackXmlOptions))
    }

    // add a copy command to the compile command to copy the SYSPRINT from the temporary dataset to an HFS log file
    compile.copy(new CopyToHFS().ddName("SYSPRINT").file(logFile).hfsEncoding(props.logEncoding))
    compile.copy(new CopyToHFS().ddName("*").file(new File("${props.buildOutDir}/${member}_compall.log")).hfsEncoding(props.logEncoding))

    return compile
}


/*
 * createLinkEditCommand - creates a MVSExec xommand for link editing the COBOL object module produced by the compile
 */
def createLinkEditCommand(String buildFile, LogicalFile logicalFile, String member, File logFile) {
    String linker = props.getFileProperty('cobol_linkEditor', buildFile)
    String linkEditStream = props.getFileProperty('cobol_linkEditStream', buildFile)
    String linkDebugExit = props.getFileProperty('cobol_linkDebugExit', buildFile)
    String parms = ""
    
    // assign the first loadPDS in the list of PDSEs as the primary
    String cobol_loadPDS = loadDatasets[0]

    // assign parameters for the bind
    if (logicalFile.isSUB())
        parms = props.getFileProperty('cobol_linkEditParmsSUB', buildFile)
    else
        parms = props.getFileProperty('cobol_linkEditParms', buildFile)

    println("Link-Edit parms for $buildFile = $parms")
    
    if (props.verbose) println "*** Link-Edit parms for $buildFile = $parms"

    // define the MVSExec command to link edit the program
    MVSExec linkedit = new MVSExec().file(buildFile).pgm(linker).parm(parms)

    // add DD statements to the linkedit command
    String deployType = buildUtils.getDeployType("cobol", buildFile, logicalFile)
    if(isZUnitTestCase){
        linkedit.dd(new DDStatement().name("SYSLMOD").dsn("${props.cobol_testcase_loadPDS}($member)").options('shr').output(true).deployType('ZUNIT-TESTCASE'))
    }
    else {
        linkedit.dd(new DDStatement().name("SYSLMOD").dsn("${cobol_loadPDS}($member)").options('shr').output(true).deployType(deployType))
    }

    // add SYSDEFSD to avoid IEW2689W 4C40 DEFINITION SIDE FILE IS NOT DEFINED message from program binder
    linkedit.dd(new DDStatement().name("SYSDEFSD").options(props.cobol_tempOptions))
    linkedit.dd(new DDStatement().name("SYSPRINT").options(props.cobol_printTempOptions))
    linkedit.dd(new DDStatement().name("SYSUT1").options(props.cobol_tempOptions))

    // Assemble linkEditInstream to define SYSIN as instreamData
    String sysin_linkEditInstream = "INCLUDE SYSLIB(${member})\n"
    
    // appending configured linkEdit stream if specified
    if (linkEditStream) {
        sysin_linkEditInstream += "  " + linkEditStream.replace("\\n","\n").replace('@{member}',member)
    }

    if (logicalFile.isFSUB()) {
        sysin_linkEditInstream += "  INCLUDE SYSLIB(DSNULI)"
    }
    
    // appending mq stub according to file flags
    if(buildUtils.isMQ(logicalFile)) {
        sysin_linkEditInstream += buildUtils.getMqStubInstruction(logicalFile)
    }

    // appending debug exit to link instructions
    if (props.debug && linkDebugExit!= null) {
        sysin_linkEditInstream += "   " + linkDebugExit.replace("\\n","\n").replace('@{member}',member)
    }

    // Define SYSIN dd
    if (sysin_linkEditInstream) 
        linkedit.dd(new DDStatement().name("SYSIN").instreamData(sysin_linkEditInstream))

    // add SYSLIN along the reference to SYSIN if configured through sysin_linkEditInstream
    def omgeving = (buildUtils.isDev(logicalFile) && !(buildUtils.isProd(logicalFile))) || (!(buildUtils.isDev(logicalFile)) && !(buildUtils.isProd(logicalFile))) ? "DEV" : "PROD"
    
    linkedit.dd(new DDStatement().name("SYSLIN").dsn("${props.cobol_objPDS}($member)").options('shr'))
    linkedit.dd(new DDStatement().dsn("CEE.SCEELIB(C128N)").options("shr"))    
    linkedit.dd(new DDStatement().dsn("CBC.SCLBSID(IOSTREAM)").options("shr"))    
    linkedit.dd(new DDStatement().dsn("CBC.SCLBSID(COMPLEX)").options("shr"))
    linkedit.dd(new DDStatement().dsn("${omgeving}.PARMLIB(IMPORTS)").options("shr"))
    linkedit.dd(new DDStatement().dsn("DEV.PARMLIB(IMPORTS)").options("shr"))
            
    // add RESLIB if needed
    if ( props.RESLIB ) 
        linkedit.dd(new DDStatement().name("RESLIB").dsn(props.RESLIB).options("shr"))

    // add a syslib to the compile command with optional concatenation
    linkedit.dd(new DDStatement().name("SYSLIB").dsn(cobol_loadPDS).options("shr"))
    
    // add custom concatenation
    def linkEditSyslibConcatenation = props.getFileProperty('cobol_linkEditSyslibConcatenation', buildFile) ?: ""
    def linkEditSyslibAddConcatenation = props.cobol_linkEditSyslibAddConcat

    // if IMS add syslib concat
    if (logicalFile.isIMS()) {
        imsReslib = (logicalFile.isDev()) ? 'DEV.IMS.RESLIB':'PROD.IMS.RESLIB'
        linkEditSyslibConcatenation = "$imsReslib,$linkEditSyslibConcatenation"
    }

    // add SDSNLOAD to the syslib concat
    if (logicalFile.isDB2() || logicalFile.isFSUB()) {
        db2Load = (logicalFile.isDev()) ? ['DB2.DBO1.SDSNLOAD', 'DB2.DBO2.SDSNLOAD']:['DB2.DBP1.SDSNLOAD', 'DB2.DBP2.SDSNLOAD']
        (linkEditSyslibConcatenation?.trim()) ? (linkEditSyslibConcatenation = linkEditSyslibConcatenation + ',' + db2Load.join(',')):(linkEditSyslibConcatenation = db2Load.join(','))
    }

    // if extra syslib concat in Cobol.properties then add
    if (linkEditSyslibConcatenation && linkEditSyslibAddConcatenation)
        linkEditSyslibConcatenation = linkEditSyslibConcatenation + ',' + linkEditSyslibAddConcatenation

    if (linkEditSyslibConcatenation) {
        println("Linkedit syslib concat: ${linkEditSyslibConcatenation}")
        def String[] syslibDatasets = linkEditSyslibConcatenation.split(',');
        for (String syslibDataset : syslibDatasets )
            linkedit.dd(new DDStatement().dsn(syslibDataset).options("shr"))
    }

    // if you want extra lib concat but not in syslib
    def linkEditAddlibConcat = props.cobol_linkEditAddlibConcat
    if (linkEditAddlibConcat) {
        println("Linkedit altlib concat: $linkEditAddlibConcat")
        def String[] addlibDatasets = linkEditAddlibConcat.split(',');
        def int teller = 0;
        for (String addlibDataset : addlibDatasets) {
            if (teller == 0)
                linkedit.dd(new DDStatement().name("ALTLIB").dsn(addlibDataset).options("shr"))
            else
                linkedit.dd(new DDStatement().dsn(addlibDataset).options("shr"))
            teller ++
        }
    }

    linkedit.dd(new DDStatement().dsn(props.SCEELKED).options("shr"))

    // Add Debug Dataset to find the debug exit to SYSLIB
    if (props.debug && props.SEQAMOD)
        linkedit.dd(new DDStatement().dsn(props.SEQAMOD).options("shr"))

    if (buildUtils.isMQ(logicalFile))
        linkedit.dd(new DDStatement().dsn(props.SCSQLOAD).options("shr"))

    // add a copy command to the linkedit command to append the SYSPRINT from the temporary dataset to the HFS log file
    linkedit.copy(new CopyToHFS().ddName("SYSPRINT").file(logFile).hfsEncoding(props.logEncoding).append(true))
    
    if (logicalFile.isFSUB())
        linkedit.copy(new CopyToHFS().ddName("SYSDEFSD").file(new File("${props.buildOutDir}/${member}_sysdefsd.imp")))
    
    return linkedit
}

/*
 * createObjectDeleteCommand - creates a MVSExec command for deleting the COBOL object that was generated during compile/bind 
 */
def createObjectDeleteCommand(String buildFile, String dataset, String member, File logFile) {
    MVSExec deleteObj = new MVSExec().file(buildFile).pgm("IDCAMS")
    
    deleteObj.dd(new DDStatement().name("SYSPRINT").options(props.cobol_printTempOptions))
    deleteObj.dd(new DDStatement().name("SYSIN").instreamData(" DELETE '$dataset($member)'"))

    deleteObj.copy(new CopyToHFS().ddName("SYSPRINT").file(logFile).hfsEncoding(props.logEncoding).append(true))

    return deleteObj
}

/*
 * addImportStatements - searches through the MDECK file generated at compile/bind time and adds new 
 * imports to the existing IMPORTS file on z/OS. In case of duplicate entry it won't perform the addition
 * of the import statement. 
 */
def addImportStatements(String member) {
    // copy original IMPORTS file to HFS
    def imports = new CopyToHFS()
    imports.setDataset("DEV.PARMLIB")
    imports.setMember("IMPORTS")
    imports.setFile(new File("${props.buildOutDir}/imports.imp"))
    imports.execute()

    // search IMPORTS file if there is already a import statement for the application
    String SEARCH_STR = "'$member','"
    def importsFile = new File("${props.buildOutDir}/imports.imp")
    def result = ""

    // assign deck file 
    deckFile = new File("${props.buildOutDir}/${member}_sysdefsd.imp")

    // search deck file for the search string 
    if (deckFile.text.contains(SEARCH_STR)) {
        deckFile.withReader { reader -> 
            while ((line = reader.readLine()) != null) {
                if (line.contains(SEARCH_STR) && !(line.contains('_')) && !(importsFile.text.contains(line))) 
                    result += " ${line.trim()}\n"
            }
        }
    }

    // append the result to the IMPORTS file
    importsFile.append(result)

    // copy the changed IMPORTS file to PDS 
    if (result) {
        def newImports = new CopyToPDS()
        newImports.setFile(importsFile)
        newImports.setDataset("DEV.PARMLIB")
        newImports.setMember("IMPORTS")
        newImports.execute()
    }

    // clean up directory
    if (importsFile.exists())
        importsFile.delete()
    if (deckFile.exists())
        deckFile.delete()
}

boolean buildListContainsTests(List<String> buildList) {
    boolean containsZUnitTestCase = buildList.find { buildFile -> props.getFileProperty('cobol_testcase', buildFile).equals('true')}
    return containsZUnitTestCase ? true : false
}