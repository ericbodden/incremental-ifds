#!/bin/sh

java -Xmx35g -cp bin:guava-13.0.jar:soot-2.5.0.jar:junit-4.10.jar soot.jimple.interproc.ifds.test.SingleJUnitTestRunner soot.jimple.interproc.ifds.test.IFDSTestReachingDefinitionsDynamic#simpleTestJU_Rerun > output_simpleTestJU_Rerun.log 2> error_simpleTestJU_Rerun.log
java -Xmx35g -cp bin:guava-13.0.jar:soot-2.5.0.jar:junit-4.10.jar soot.jimple.interproc.ifds.test.SingleJUnitTestRunner soot.jimple.interproc.ifds.test.IFDSTestReachingDefinitionsDynamic#simpleTestJU_Propagate > output_simpleTestJU_Propagate.log 2> error_simpleTestJU_Propagate.log

java -Xmx35g -cp bin:guava-13.0.jar:soot-2.5.0.jar:junit-4.10.jar soot.jimple.interproc.ifds.test.SingleJUnitTestRunner soot.jimple.interproc.ifds.test.IFDSTestReachingDefinitionsDynamic#addLocalJU_Rerun > output_addLocalJU_Rerun.log 2> error_addLocalJU_Rerun.log
java -Xmx35g -cp bin:guava-13.0.jar:soot-2.5.0.jar:junit-4.10.jar soot.jimple.interproc.ifds.test.SingleJUnitTestRunner soot.jimple.interproc.ifds.test.IFDSTestReachingDefinitionsDynamic#addLocalJU_Propagate > output_addLocalJU_Propagate.log 2> error_addLocalJU_Propagate.log

java -Xmx35g -cp bin:guava-13.0.jar:soot-2.5.0.jar:junit-4.10.jar soot.jimple.interproc.ifds.test.SingleJUnitTestRunner soot.jimple.interproc.ifds.test.IFDSTestReachingDefinitionsDynamic#redefineVarJU_Rerun > output_redefineVarJU_Rerun.log 2> error_redefineVarJU_Rerun.log
java -Xmx35g -cp bin:guava-13.0.jar:soot-2.5.0.jar:junit-4.10.jar soot.jimple.interproc.ifds.test.SingleJUnitTestRunner soot.jimple.interproc.ifds.test.IFDSTestReachingDefinitionsDynamic#redefineVarJU_Propagate > output_redefineVarJU_Propagate.log 2> error_redefineVarJU_Propagate.log

java -Xmx35g -cp bin:guava-13.0.jar:soot-2.5.0.jar:junit-4.10.jar soot.jimple.interproc.ifds.test.SingleJUnitTestRunner soot.jimple.interproc.ifds.test.IFDSTestReachingDefinitionsDynamic#removeStmtJU_Rerun > output_removeStmtJU_Rerun.log 2> error_removeStmtJU_Rerun.log
java -Xmx35g -cp bin:guava-13.0.jar:soot-2.5.0.jar:junit-4.10.jar soot.jimple.interproc.ifds.test.SingleJUnitTestRunner soot.jimple.interproc.ifds.test.IFDSTestReachingDefinitionsDynamic#removeStmtJU_Propagate > output_removeStmtJU_Propagate.log 2> error_removeStmtJU_Propagate.log

java -Xmx35g -cp bin:guava-13.0.jar:soot-2.5.0.jar:junit-4.10.jar soot.jimple.interproc.ifds.test.SingleJUnitTestRunner soot.jimple.interproc.ifds.test.IFDSTestReachingDefinitionsDynamic#removeAssignmentJU_Rerun > output_removeAssignmentJU_Rerun.log 2> error_removeAssignmentJU_Rerun.log
java -Xmx35g -cp bin:guava-13.0.jar:soot-2.5.0.jar:junit-4.10.jar soot.jimple.interproc.ifds.test.SingleJUnitTestRunner soot.jimple.interproc.ifds.test.IFDSTestReachingDefinitionsDynamic#removeAssignmentJU_Propagate > output_removeAssignmentJU_Propagate.log 2> error_removeAssignmentJU_Propagate.log

java -Xmx35g -cp bin:guava-13.0.jar:soot-2.5.0.jar:junit-4.10.jar soot.jimple.interproc.ifds.test.SingleJUnitTestRunner soot.jimple.interproc.ifds.test.IFDSTestReachingDefinitionsDynamic#addCallNoAssignmentJU_Rerun > output_addCallNoAssignmentJU_Rerun.log 2> error_addCallNoAssignmentJU_Rerun.log
java -Xmx35g -cp bin:guava-13.0.jar:soot-2.5.0.jar:junit-4.10.jar soot.jimple.interproc.ifds.test.SingleJUnitTestRunner soot.jimple.interproc.ifds.test.IFDSTestReachingDefinitionsDynamic#addCallNoAssignmentJU_Propagate > output_addCallNoAssignmentJU_Propagate.log 2> error_addCallNoAssignmentJU_Propagate.log

java -Xmx35g -cp bin:guava-13.0.jar:soot-2.5.0.jar:junit-4.10.jar soot.jimple.interproc.ifds.test.SingleJUnitTestRunner soot.jimple.interproc.ifds.test.IFDSTestReachingDefinitionsDynamic#addCallAssignmentJU_Rerun > output_addCallAssignmentJU_Rerun.log 2> error_addCallAssignmentJU_Rerun.log
java -Xmx35g -cp bin:guava-13.0.jar:soot-2.5.0.jar:junit-4.10.jar soot.jimple.interproc.ifds.test.SingleJUnitTestRunner soot.jimple.interproc.ifds.test.IFDSTestReachingDefinitionsDynamic#addCallAssignmentJU_Propagate > output_addCallAssignmentJU_Propagate.log 2> error_addCallAssignmentJU_Propagate.log

java -Xmx35g -cp bin:guava-13.0.jar:soot-2.5.0.jar:junit-4.10.jar soot.jimple.interproc.ifds.test.SingleJUnitTestRunner soot.jimple.interproc.ifds.test.IFDSTestReachingDefinitionsDynamic#removeStmtFromLoopJU_Rerun > output_removeStmtFromLoopJU_Rerun.log 2> error_removeStmtFromLoopJU_Rerun.log
java -Xmx35g -cp bin:guava-13.0.jar:soot-2.5.0.jar:junit-4.10.jar soot.jimple.interproc.ifds.test.SingleJUnitTestRunner soot.jimple.interproc.ifds.test.IFDSTestReachingDefinitionsDynamic#removeStmtFromLoopJU_Propagate > output_removeStmtFromLoopJU_Propagate.log 2> error_removeStmtFromLoopJU_Propagate.log

java -Xmx35g -cp bin:guava-13.0.jar:soot-2.5.0.jar:junit-4.10.jar soot.jimple.interproc.ifds.test.SingleJUnitTestRunner soot.jimple.interproc.ifds.test.IFDSTestReachingDefinitionsDynamic#redefineReturnJU_Rerun > output_redefineReturnJU_Rerun.log 2> error_redefineReturnJU_Rerun.log
java -Xmx35g -cp bin:guava-13.0.jar:soot-2.5.0.jar:junit-4.10.jar soot.jimple.interproc.ifds.test.SingleJUnitTestRunner soot.jimple.interproc.ifds.test.IFDSTestReachingDefinitionsDynamic#redefineReturnJU_Propagate > output_redefineReturnJU_Propagate.log 2> error_redefineReturnJU_Propagate.log
