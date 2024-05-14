@groovy.transform.BaseScript com.ibm.dbb.groovy.ScriptLoader baseScript
import com.ibm.dbb.build.*
import com.ibm.dbb.metadata.*
import com.ibm.dbb.dependency.*
import groovy.transform.*
import groovy.cli.commons.*

/**
 * This script builds a DB2 application package for SQL programs in the application.
 */

bind(args)

// Make the DB2 BIND PACKAGE CLIST and have it copied to your DBRMhlq
def makeBindPackage(String file, String dbrmHLQ, String workDir, String SUBSYS, String OWNER, String QUAL) {
    // define local properties
    def MEMBER = CopyToPDS.createMemberName(file)
    def COLLID = "${SUBSYS}.DEFAULT"
    def LIB = dbrmHLQ.split('\\.', 2)[1]

    def clistPDS = "${dbrmHLQ}.CLIST.$MEMBER"
    def srcOptions = "cyl space(1,1) lrecl(80) dsorg(PO) recfm(F,B) dsntype(library) msg(1)"

    // create PACKAGE CLIST if necessary
    def clistBindPackage = new File("${workDir}/${MEMBER}_package.clist")
    if (clistBindPackage.exists())
        clistBindPackage.delete()

    clistBindPackage << """
        DSN SYSTEM($SUBSYS) RETRY(10) 
        BIND PACKAGE($COLLID)                        + 
            LIBRARY($LIB)                            +       
            OWNER($OWNER)                            +
            MEMBER($MEMBER)                          + 
            DEFER(PREPARE)                           +
            KEEPDYNAMIC(NO)                          +
            NOREOPT(VARS)                            +
            RELEASE(DEALLOCATE)                      + 
            CURRENTDATA(NO)                          + 
            QUALIFIER($QUAL)                         +
            DEGREE(1)                                +
            VALIDATE(BIND)                           + 
            ISOLATION(CS)                            +
            EXPLAIN(YES)                             +
            PATH(SIDUDT,SIDFUN,SIDPROC,SYSIBM,SYSFUN,SYSPROC,USER)
        END
    """.toString()

    // Create CLIST PDS if necessary
    new CreatePDS().dataset(clistPDS).options(srcOptions).create()

    // Save CLIST to PDS
    new CopyToPDS().file(clistBindPackage).dataset(clistPDS).member("PACKAGE").execute()

    return "$clistPDS(PACKAGE)"
}

// Make the DB2 BIND PLAN CLIST and have it copied to your DBRMhlq
def makeBindPlan(String file, String dbrmHLQ, String workDir, String SUBSYS) {
    // define local properties
    def MEMBER = CopyToPDS.createMemberName(file)

    def clistPDS = "${dbrmHLQ}.CLIST.$MEMBER"
    def srcOptions = "cyl space(1,1) lrecl(80) dsorg(PO) recfm(F,B) dsntype(library) msg(1)"

    // create PLAN CLIST if necessary
    def clistBindPlan = new File("${workDir}/${MEMBER}_plan.clist")
    if (clistBindPlan.exists())
        clistBindPlan.delete()

    clistBindPlan << """
        DSN SYSTEM($SUBSYS) RETRY(10) 
        BIND PLAN($MEMBER)                                            +
            OWNER(****)                                            +
            ACTION(REPLACE) RETAIN                                    +
            DISCONNECT(EXPLICIT)                                      +
            PATH(SIDUDT,SIDFUN,SIDPROC,SYSIBM,SYSFUN,SYSPROC,USER)    +
            PKLIST(*.DEFAULT.*)
        END
    """.toString()

    // Create CLIST PDS if necessary
    new CreatePDS().dataset(clistPDS).options(srcOptions).create()

    // Save CLIST to PDS
    new CopyToPDS().file(clistBindPlan).dataset(clistPDS).member("PLAN").execute()

    return "$clistPDS(PLAN)"
}