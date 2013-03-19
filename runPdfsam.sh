#!/bin/sh

java -ea -Xmx35g -cp bin:guava-13.0.jar:soot-2.5.0.jar:junit-4.10.jar soot.jimple.interproc.ifds.test.SingleJUnitTestRunner soot.jimple.interproc.ifds.test.IFDSTestPDFsam#newVersionSH_Propagate > output_newVersionSH_Propagate.log 2> error_newVersionSH_Propagate.log
